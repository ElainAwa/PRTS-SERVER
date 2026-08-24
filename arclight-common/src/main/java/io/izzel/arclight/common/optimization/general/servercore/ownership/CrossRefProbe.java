package io.izzel.arclight.common.optimization.general.servercore.ownership;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionLevel;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Samples getBlockEntity accesses on region/dimension workers, bucketing each
 * caller (owner) into cross-region vs within-region hits to scope shim design.
 * Its on/off state is independent of the ownership guard's thread policy.
 */
public final class CrossRefProbe {

    private static final int BUCKET_COUNT = 60;
    private static final int BUCKET_TICKS = 100;
    private static final int MAX_TOP_POS = 8;

    private static volatile boolean enabled = false;
    private static volatile boolean snapshotEnabled = false;
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private CrossRefProbe() {
    }

    public static void applyConfig(boolean probe, boolean snapshot) {
        enabled = probe;
        snapshotEnabled = snapshot;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean snapshotEnabled() {
        return snapshotEnabled;
    }

    /** Records one getBlockEntity access from a region/dimension worker. */
    public static void recordGetBlockEntity(Level level, BlockPos pos) {
        if (!enabled) return;
        // Only region/dimension workers are sampled; main-thread traffic is out of scope.
        if (!RegionTickManager.isRegionWorker() && !DimensionTickManager.isDimensionTickThread()) return;
        String owner = RegionTickManager.currentEntityClassName();
        if (owner == null || owner.isEmpty()) {
            owner = Thread.currentThread().getName();
        }
        MinecraftServer server = level.getServer();
        long tick = server != null ? server.getTickCount() : 0L;
        int region = RegionTickManager.currentRegion();
        boolean cross = region >= 0 && RegionLevel.regionId(pos.getX() >> 4) != region;
        Entry entry = ENTRIES.computeIfAbsent(owner, k -> new Entry(tick));
        entry.record(tick, cross, pos);
        if (snapshotEnabled && entry.claimShadowSample(tick)) {
            BlockEntitySnapshotAdapter.shadow(level, pos);
        }
    }

    public static String statusText() {
        long total = 0;
        long cross = 0;
        for (Entry e : ENTRIES.values()) {
            total += e.total.sum();
            cross += e.cross.sum();
        }
        return (enabled ? "on" : "off") + " total=" + total + " cross=" + cross
                + " owners=" + ENTRIES.size() + " | snapshot " + BlockEntitySnapshotAdapter.statsText();
    }

    public static void reset() {
        ENTRIES.clear();
        BlockEntitySnapshotAdapter.reset();
    }

    /** Builds a human-readable report, sorted by total desc (top limit owners). */
    public static List<String> report(long currentTick, int limit) {
        List<String> lines = new ArrayList<>();
        lines.add(BlockEntitySnapshotAdapter.statsText() + " | last=" + BlockEntitySnapshotAdapter.lastSummary());
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(ENTRIES.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().total.sum(), a.getValue().total.sum()));
        int n = Math.min(limit, sorted.size());
        for (int i = 0; i < n; i++) {
            Map.Entry<String, Entry> me = sorted.get(i);
            Entry e = me.getValue();
            long t = e.total.sum();
            long c = e.cross.sum();
            long active = e.ticksActive.sum();
            double avg = active > 0 ? (double) t / active : 0.0;
            lines.add(String.format(" %s: total=%d cross=%d ticksActive=%d avg=%.1f", me.getKey(), t, c, active, avg));
            lines.add("   pos: " + e.posSummary());
            lines.add("   tick:" + e.bucketSummary(currentTick));
        }
        return lines;
    }

    private static final class Entry {
        final long firstTick;
        volatile long lastTick;
        final LongAdder total = new LongAdder();
        final LongAdder cross = new LongAdder();
        final LongAdder ticksActive = new LongAdder();
        final AtomicLong lastActiveTick = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong lastPosSampleTick = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong lastShadowSampleTick = new AtomicLong(Long.MIN_VALUE);
        final AtomicInteger[] buckets = new AtomicInteger[BUCKET_COUNT];
        final int[] bucketEpoch = new int[BUCKET_COUNT];
        final LinkedHashMap<BlockPos, Integer> topPos = new LinkedHashMap<>(MAX_TOP_POS, 0.75f, true);

        Entry(long tick) {
            firstTick = tick;
            lastTick = tick;
            for (int i = 0; i < BUCKET_COUNT; i++) {
                buckets[i] = new AtomicInteger();
            }
        }

        void record(long tick, boolean isCross, BlockPos pos) {
            total.increment();
            if (isCross) cross.increment();
            lastTick = tick;
            long last = lastActiveTick.get();
            if (last != tick && lastActiveTick.compareAndSet(last, tick)) {
                ticksActive.increment();
            }
            int epoch = (int) (tick / BUCKET_TICKS);
            int idx = Math.floorMod(epoch, BUCKET_COUNT);
            if (bucketEpoch[idx] != epoch) {
                buckets[idx].set(0);
                bucketEpoch[idx] = epoch;
            }
            buckets[idx].incrementAndGet();
            if (claim(lastPosSampleTick, tick)) {
                samplePos(pos);
            }
        }

        private boolean claim(AtomicLong latch, long tick) {
            long last = latch.get();
            return last != tick && latch.compareAndSet(last, tick);
        }

        boolean claimShadowSample(long tick) {
            return claim(lastShadowSampleTick, tick);
        }

        private void samplePos(BlockPos pos) {
            synchronized (topPos) {
                Integer cur = topPos.get(pos);
                if (cur != null) {
                    topPos.put(pos, cur + 1);
                    return;
                }
                // Full: a new pos starts at count 1 and cannot exceed any existing entry.
                if (topPos.size() < MAX_TOP_POS) {
                    topPos.put(pos, 1);
                }
            }
        }

        String posSummary() {
            List<Map.Entry<BlockPos, Integer>> top = new ArrayList<>();
            synchronized (topPos) {
                top.addAll(topPos.entrySet());
            }
            top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(3, top.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(' ');
                sb.append(top.get(i).getKey().toShortString()).append('(').append(top.get(i).getValue()).append(')');
            }
            return sb.toString();
        }

        String bucketSummary(long currentTick) {
            StringBuilder sb = new StringBuilder();
            int curEpoch = (int) (currentTick / BUCKET_TICKS);
            for (int b = 0; b < 6; b++) {
                int epoch = curEpoch - b;
                int idx = Math.floorMod(epoch, BUCKET_COUNT);
                long v = bucketEpoch[idx] == epoch ? buckets[idx].get() : 0;
                if (b > 0) sb.append(' ');
                sb.append(v);
            }
            return sb.toString();
        }
    }
}
