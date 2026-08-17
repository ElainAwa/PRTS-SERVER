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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cross-region world-write journal. Region workers submit block writes that land
 * outside their authoritative region; the owning region's worker applies them at
 * the start of its next session.
 *
 * <p>Bounds and observability: each per-region queue has a hard cap (oldest entry
 * dropped), entries are discarded when their target chunk is no longer live, and
 * submitted/applied/dropped counters feed /servercore status.</p>
 */
public final class WorldWriteJournal {

    public record Entry(BlockPos pos, BlockState state, int flags, long submitTick, String sourceClass) {
    }

    private final ConcurrentLinkedQueue<Entry>[] queues;
    private final int maxPerRegion;
    private final LongAdder submitted = new LongAdder();
    private final LongAdder applied = new LongAdder();
    private final LongAdder droppedOverflow = new LongAdder();
    private final LongAdder droppedUnloaded = new LongAdder();
    private final LongAdder failed = new LongAdder();

    @SuppressWarnings("unchecked")
    public WorldWriteJournal(int regionCount, int maxPerRegion) {
        this.maxPerRegion = Math.max(16, maxPerRegion);
        this.queues = new ConcurrentLinkedQueue[Math.max(1, regionCount)];
        for (int i = 0; i < this.queues.length; i++) {
            this.queues[i] = new ConcurrentLinkedQueue<>();
        }
    }

    public boolean submit(int regionId, Entry entry) {
        if (regionId < 0 || regionId >= this.queues.length) {
            return false;
        }
        ConcurrentLinkedQueue<Entry> queue = this.queues[regionId];
        synchronized (queue) {
            if (queue.size() >= this.maxPerRegion) {
                queue.poll();
                this.droppedOverflow.increment();
            }
            queue.add(entry);
        }
        this.submitted.increment();
        return true;
    }

    /** Drains one region queue and applies entries whose target chunk is still live. */
    public int apply(ServerLevel level, int regionId) {
        if (regionId < 0 || regionId >= this.queues.length) {
            return 0;
        }
        ConcurrentLinkedQueue<Entry> queue = this.queues[regionId];
        List<Entry> batch = new ArrayList<>();
        synchronized (queue) {
            Entry entry;
            while ((entry = queue.poll()) != null) {
                batch.add(entry);
            }
        }
        if (batch.isEmpty()) {
            return 0;
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
        for (ConcurrentLinkedQueue<Entry> queue : this.queues) {
            if (!queue.isEmpty()) {
                return false;
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
        // integration). Linear scan is acceptable because callers are expected to
        // enable it only for small queues / specific mods.
        for (Entry entry : this.queues[regionId]) {
            if (entry.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public String statusText() {
        long pending = 0;
        for (ConcurrentLinkedQueue<Entry> queue : this.queues) {
            pending += queue.size();
        }
        return "pending=" + pending
                + " submitted=" + this.submitted.sum()
                + " applied=" + this.applied.sum()
                + " droppedOverflow=" + this.droppedOverflow.sum()
                + " droppedUnloaded=" + this.droppedUnloaded.sum()
                + " failed=" + this.failed.sum();
    }
}
