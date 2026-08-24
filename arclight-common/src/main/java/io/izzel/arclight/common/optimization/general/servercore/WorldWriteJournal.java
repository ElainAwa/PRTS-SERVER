/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cross-region world-write journal: workers submit block writes outside their region;
 * the owning region's worker applies them next tick. Queues are bounded (oldest dropped),
 * entries targeting unloaded chunks are discarded; with LWW dedup, repeated writes to the
 * same pending pos collapse to the newest entry before application (last-write-wins).
 */
public final class WorldWriteJournal {

    public record Entry(BlockPos pos, BlockState state, int flags, long submitTick, String sourceClass) {
    }

    /** lwwDedup on: LinkedHashMap<BlockPos, Entry> (pos-keyed, insertion-ordered); off: ConcurrentLinkedQueue<Entry>. */
    private final Object[] queues;
    private final boolean lwwDedup;
    private final int maxPerRegion;
    private final LongAdder submitted = new LongAdder();
    private final LongAdder applied = new LongAdder();
    private final LongAdder droppedOverflow = new LongAdder();
    private final LongAdder droppedUnloaded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder lwwMerged = new LongAdder();
    private final LongAdder budgetDropped = new LongAdder();

    @SuppressWarnings("unchecked")
    public WorldWriteJournal(int regionCount, int maxPerRegion, boolean lwwDedup) {
        this.maxPerRegion = Math.max(16, maxPerRegion);
        this.lwwDedup = lwwDedup;
        this.queues = new Object[Math.max(1, regionCount)];
        for (int i = 0; i < this.queues.length; i++) {
            this.queues[i] = lwwDedup ? new LinkedHashMap<BlockPos, Entry>() : new ConcurrentLinkedQueue<Entry>();
        }
    }

    public boolean submit(int regionId, Entry entry) {
        if (regionId < 0 || regionId >= this.queues.length) {
            return false;
        }
        if (this.lwwDedup) {
            @SuppressWarnings("unchecked")
            LinkedHashMap<BlockPos, Entry> map = (LinkedHashMap<BlockPos, Entry>) this.queues[regionId];
            synchronized (map) {
                if (map.size() >= this.maxPerRegion) {
                    map.remove(map.keySet().iterator().next());
                    this.droppedOverflow.increment();
                }
                // Same-pos pending entry collapses to the newest write (LWW, pre-apply).
                if (map.remove(entry.pos()) != null) {
                    this.lwwMerged.increment();
                    RegionTickManager.noteJournalLwwMerge();
                }
                map.put(entry.pos(), entry);
            }
        } else {
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<Entry> queue = (ConcurrentLinkedQueue<Entry>) this.queues[regionId];
            synchronized (queue) {
                if (queue.size() >= this.maxPerRegion) {
                    queue.poll();
                    this.droppedOverflow.increment();
                }
                queue.add(entry);
            }
        }
        this.submitted.increment();
        return true;
    }

    /** Drains one region queue and applies entries whose target chunk is still live. */
    public int apply(ServerLevel level, int regionId) {
        if (regionId < 0 || regionId >= this.queues.length) {
            return 0;
        }
        List<Entry> batch;
        if (this.lwwDedup) {
            @SuppressWarnings("unchecked")
            LinkedHashMap<BlockPos, Entry> map = (LinkedHashMap<BlockPos, Entry>) this.queues[regionId];
            synchronized (map) {
                if (map.isEmpty()) {
                    return 0;
                }
                // Snapshot + clear under the same lock: the batch is dequeued, so any
                // concurrent submit starts a fresh entry instead of merging into it.
                batch = new ArrayList<>(map.values());
                map.clear();
            }
        } else {
            @SuppressWarnings("unchecked")
            ConcurrentLinkedQueue<Entry> queue = (ConcurrentLinkedQueue<Entry>) this.queues[regionId];
            batch = new ArrayList<>();
            synchronized (queue) {
                Entry entry;
                while ((entry = queue.poll()) != null) {
                    batch.add(entry);
                }
            }
            if (batch.isEmpty()) {
                return 0;
            }
        }
        int appliedNow = 0;
        for (Entry entry : batch) {
            ChunkPos pos = new ChunkPos(entry.pos());
            if (!((ServerChunkCacheRegionBridge) level.getChunkSource()).arclight$hasLiveChunk(pos.x, pos.z)) {
                this.droppedUnloaded.increment();
                continue;
            }
            try {
                level.setBlock(entry.pos(), entry.state(), entry.flags());
                this.applied.increment();
                appliedNow++;
            } catch (Throwable t) {
                this.failed.increment();
            }
        }
        return appliedNow;
    }

    /** Determinism mode: drains every region queue in region order on the scheduling thread. */
    public int applyAll(ServerLevel level) {
        int total = 0;
        for (int regionId = 0; regionId < this.queues.length; regionId++) {
            total += apply(level, regionId);
        }
        return total;
    }

    public boolean isEmpty() {
        for (Object queue : this.queues) {
            synchronized (queue) {
                if (this.lwwDedup ? !((LinkedHashMap<?, ?>) queue).isEmpty() : !((ConcurrentLinkedQueue<?>) queue).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean hasQueuedWrite(int regionId, BlockPos pos) {
        if (regionId < 0 || regionId >= this.queues.length) {
            return false;
        }
        // Read-your-writes interface (not wired into the block-read path yet; the
        // parallel.journal-read-back option keeps it opt-in for a future mod-level
        // integration). Small bounded queues keep the scan cheap.
        Object queue = this.queues[regionId];
        synchronized (queue) {
            if (this.lwwDedup) {
                return ((LinkedHashMap<?, ?>) queue).containsKey(pos);
            }
            for (Entry entry : (ConcurrentLinkedQueue<Entry>) queue) {
                if (entry.pos().equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Per-tick submit budget breaker feed; counted here so status stays one line. */
    public void recordBudgetDrop() {
        this.budgetDropped.increment();
    }

    public String statusText() {
        long pending = 0;
        for (Object queue : this.queues) {
            synchronized (queue) {
                pending += this.lwwDedup ? ((LinkedHashMap<?, ?>) queue).size() : ((ConcurrentLinkedQueue<?>) queue).size();
            }
        }
        return "pending=" + pending
                + " submitted=" + this.submitted.sum()
                + " applied=" + this.applied.sum()
                + " droppedOverflow=" + this.droppedOverflow.sum()
                + " droppedUnloaded=" + this.droppedUnloaded.sum()
                + " failed=" + this.failed.sum()
                + " lwwMerged=" + this.lwwMerged.sum()
                + " budgetDropped=" + this.budgetDropped.sum();
    }
}
