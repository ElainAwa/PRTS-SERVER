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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-side value snapshot of container block entities: chunk snapshot read,
 * then per-slot item copy, never exposing live BE references.
 */
public final class BlockEntitySnapshotAdapter {

    private static final Set<String> WHITELIST = Set.of(
            "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel",
            "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper");

    private static final LongAdder hit = new LongAdder();
    private static final LongAdder miss = new LongAdder();
    private static final LongAdder torn = new LongAdder();
    private static volatile String lastSummary = "-";

    private BlockEntitySnapshotAdapter() {
    }

    /** Shadow path called by the probe; counts hit/miss/torn for verification. */
    public static BlockEntitySnapshot shadow(Level level, BlockPos pos) {
        try {
            BlockEntitySnapshot snap = snapshot(level, pos);
            if (snap != null) {
                hit.increment();
                int filled = 0;
                for (ItemStack stack : snap.items()) {
                    if (!stack.isEmpty()) filled++;
                }
                lastSummary = snap.typeKey() + " @ " + snap.pos().toShortString() + " slots=" + filled;
            } else {
                miss.increment();
            }
            return snap;
        } catch (Throwable t) {
            torn.increment();
            return null;
        }
    }

    /** Pure value copy of a whitelisted container BE, or null on any miss. */
    public static BlockEntitySnapshot snapshot(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        ChunkSource source = serverLevel.getChunkSource();
        if (!(source instanceof ServerChunkCacheRegionBridge bridge)) return null;
        ChunkAccess chunk = bridge.arclight$getChunkForRead(pos.getX() >> 4, pos.getZ() >> 4);
        if (!(chunk instanceof LevelChunk levelChunk)) return null;
        BlockEntity be = levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
        if (be == null) return null;
        ResourceLocation typeKey = BlockEntityType.getKey(be.getType());
        if (typeKey == null || !WHITELIST.contains(typeKey.toString())) return null;
        if (!(be instanceof Container container)) return null;
        int size = container.getContainerSize();
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(container.getItem(i).copy());
        }
        return new BlockEntitySnapshot(typeKey.toString(), List.copyOf(items), java.util.Map.of(), pos.immutable());
    }

    public static String statsText() {
        return "hit=" + hit.sum() + " miss=" + miss.sum() + " torn=" + torn.sum();
    }

    public static String lastSummary() {
        return lastSummary;
    }

    public static void reset() {
        hit.reset();
        miss.reset();
        torn.reset();
        lastSummary = "-";
    }
}
