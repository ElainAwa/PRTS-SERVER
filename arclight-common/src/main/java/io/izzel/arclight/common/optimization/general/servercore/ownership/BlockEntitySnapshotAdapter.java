package io.izzel.arclight.common.optimization.general.servercore.ownership;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-side value snapshot of container block entities: chunk snapshot read,
 * then per-slot item copy, never exposing live BE references.
 *
 * Miss outcomes are attributed to three disjoint buckets (fixed check order,
 * no interleaved returns on failure paths) so M2 decisions can tell apart
 * "chunk invisible" (authoritative copy can fix), "no BE at pos" (nothing
 * can fix) and "BE off whitelist" (whitelist expansion candidates).
 */
public final class BlockEntitySnapshotAdapter {

    private static final Set<String> WHITELIST = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper");

    /** Hard cap of off-whitelist candidate entries (same top-K eviction as probe topPos). */
    private static final int MAX_CANDIDATES = 8;

    private static final LongAdder hit = new LongAdder();
    private static final LongAdder missNoBE = new LongAdder();
    private static final LongAdder missNoChunk = new LongAdder();
    private static final LongAdder missOffWhitelist = new LongAdder();
    private static final LongAdder torn = new LongAdder();
    private static final LongAdder cacheHit = new LongAdder();
    private static volatile String lastSummary = "-";

    /** Off-whitelist typeKey candidates, access-ordered; a full map never admits new keys. */
    private static final LinkedHashMap<String, Integer> OFF_WHITELIST_TOP =
            new LinkedHashMap<>(MAX_CANDIDATES, 0.75f, true);

    /**
     * Falsification-experiment single-entry cache (config parallel.crossref-snapshot-cache,
     * default off): only meaningful with crossref-value-snapshot enabled, since shadow
     * traffic comes from the probe's shadow path.
     */
    private static volatile boolean cacheEnabled = false;
    // Single resident entry: two volatiles, racy by design (probe-only; a lost race at
    // worst misses one cacheHit or serves one stale copy, never affects live world state).
    private static volatile String cacheKey;
    private static volatile BlockEntitySnapshot cacheValue;

    private BlockEntitySnapshotAdapter() {
    }

    public static void setCacheEnabled(boolean enabled) {
        cacheEnabled = enabled;
        if (!enabled) {
            cacheValue = null;
            cacheKey = null;
        }
    }

    /** Shadow path called by the probe; owns lastSummary and the cache experiment. */
    public static BlockEntitySnapshot shadow(Level level, BlockPos pos, String owner) {
        BlockEntitySnapshot snap = null;
        if (cacheEnabled && owner != null) {
            String key = owner + "@" + pos.toShortString();
            if (key.equals(cacheKey)) {
                BlockEntitySnapshot cached = cacheValue;
                if (cached != null) {
                    cacheHit.increment();
                    snap = cached;
                }
            }
            if (snap == null) {
                snap = snapshot(level, pos);
                if (snap != null) {
                    cacheValue = snap;
                    cacheKey = key;
                }
            }
        } else {
            snap = snapshot(level, pos);
        }
        if (snap != null) {
            int filled = 0;
            for (ItemStack stack : snap.items()) {
                if (!stack.isEmpty()) filled++;
            }
            lastSummary = snap.typeKey() + " @ " + snap.pos().toShortString() + " slots=" + filled;
        }
        return snap;
    }

    /**
     * Pure value copy of a whitelisted container BE, or null on any miss.
     * Check order is fixed: chunk invisible → noBE → offWhitelist → hit; exceptions → torn.
     * Counting lives here so shadow and API paths share one attribution.
     */
    public static BlockEntitySnapshot snapshot(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return countMissNoChunk(null, pos);
        }
        ChunkSource source = serverLevel.getChunkSource();
        if (!(source instanceof ServerChunkCacheRegionBridge bridge)) {
            return countMissNoChunk(null, pos);
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        ChunkAccess chunk = bridge.arclight$getChunkForRead(cx, cz);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            // Align with worker getChunk miss behavior: submit demand so the chunk can
            // be loaded by the main thread instead of the caller retrying forever.
            return countMissNoChunk(bridge, pos);
        }
        BlockEntity be = levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (be == null) {
            missNoBE.increment();
            return null;
        }
        ResourceLocation typeKey = BlockEntityType.getKey(be.getType());
        String typeKeyStr = typeKey != null ? typeKey.toString() : be.getClass().getName();
        if (!WHITELIST.contains(typeKeyStr)) {
            missOffWhitelist.increment();
            recordCandidate(typeKeyStr);
            return null;
        }
        if (!(be instanceof Container container)) {
            torn.increment();
            return null;
        }
        try {
            int size = container.getContainerSize();
            List<ItemStack> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                items.add(container.getItem(i).copy());
            }
            hit.increment();
            return new BlockEntitySnapshot(typeKeyStr, List.copyOf(items), java.util.Map.of(), pos.immutable());
        } catch (Throwable t) {
            torn.increment();
            return null;
        }
    }

    private static BlockEntitySnapshot countMissNoChunk(ServerChunkCacheRegionBridge bridge, BlockPos pos) {
        missNoChunk.increment();
        if (bridge != null) {
            bridge.arclight$submitChunkDemand(pos.getX() >> 4, pos.getZ() >> 4);
        }
        return null;
    }

    private static void recordCandidate(String typeKey) {
        synchronized (OFF_WHITELIST_TOP) {
            Integer cur = OFF_WHITELIST_TOP.get(typeKey);
            if (cur != null) {
                OFF_WHITELIST_TOP.put(typeKey, cur + 1);
                return;
            }
            // Full: a new key starts at count 1 and cannot exceed any existing entry.
            if (OFF_WHITELIST_TOP.size() < MAX_CANDIDATES) {
                OFF_WHITELIST_TOP.put(typeKey, 1);
            }
        }
    }

    /** Report line: off-whitelist candidates sorted by count desc. */
    public static String candidatesText() {
        List<Map.Entry<String, Integer>> top;
        synchronized (OFF_WHITELIST_TOP) {
            top = new ArrayList<>(OFF_WHITELIST_TOP.entrySet());
        }
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("off-whitelist candidates:");
        for (Map.Entry<String, Integer> e : top) {
            sb.append(' ').append(e.getKey()).append('(').append(e.getValue()).append(')');
        }
        return sb.toString();
    }

    /**
     * hit/miss(torn) stats; miss conserves as the sum of its three attributions.
     */
    public static String statsText() {
        long noBE = missNoBE.sum();
        long noChunk = missNoChunk.sum();
        long off = missOffWhitelist.sum();
        String s = "hit=" + hit.sum()
                + " miss=" + (noBE + noChunk + off)
                + "(noBE=" + noBE + " noChunk=" + noChunk + " offWhitelist=" + off + ")"
                + " torn=" + torn.sum();
        if (cacheEnabled) {
            s += " cacheHit=" + cacheHit.sum();
        }
        return s;
    }

    public static String lastSummary() {
        return lastSummary;
    }

    public static void reset() {
        hit.reset();
        missNoBE.reset();
        missNoChunk.reset();
        missOffWhitelist.reset();
        torn.reset();
        cacheHit.reset();
        synchronized (OFF_WHITELIST_TOP) {
            OFF_WHITELIST_TOP.clear();
        }
        cacheValue = null;
        cacheKey = null;
        lastSummary = "-";
    }
}
