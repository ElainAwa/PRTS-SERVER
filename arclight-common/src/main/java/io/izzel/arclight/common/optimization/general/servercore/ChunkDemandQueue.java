/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一异步 Chunk 需求调度（v03）：所有线程的 chunk 生成请求改为异步提交——
 * 未就绪时注册等待 future 并返回空壳占位，主线程 tick / worldgen 完成生成后
 * 经 completeChunk 通知所有等待者（详见 docs/parallel-chunk-demand-scheduling-v01.md 附录 A）。
 */
public final class ChunkDemandQueue {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkDemand");

    /** 主线程 tick 处理的 chunk 需求上限（配置 parallel.chunk-demand-per-tick）。 */
    public static volatile int maxPerTick = 50;

    /** 硬性上限：超过则丢弃需求，防止队列无界增长导致 OOM。 */
    private static final int MAX_PENDING = 1024;

    /** 待加载需求按 ServerLevel 隔离，防止同坐标跨维度被错误消费。 */
    private static final ConcurrentHashMap<ServerLevel, ConcurrentLinkedQueue<Demand>> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap.KeySetView<DemandKey, Boolean> SEEN = ConcurrentHashMap.newKeySet();

    /** 等待 future 注册表：维度与 chunk 坐标 → 等待者，生成完成后统一 complete。 */
    private static final ConcurrentHashMap<DemandKey, List<CompletableFuture<ChunkAccess>>> WAITERS = new ConcurrentHashMap<>();

    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();

    private ChunkDemandQueue() {
    }

    /**
     * 提交 chunk 需求并注册等待 future。immediate=true（主线程调用）时立即调度生成
     * （worldgen 异步执行，完成后经 completeChunk 通知）；否则仅入队，由主线程 tick
     * 消费。生成完成后 future 会被 completeChunk 完成（可 await 有界等待）。
     */
    public static CompletableFuture<ChunkAccess> submitWait(ServerLevel level, ChunkMap chunkMap, int x, int z, boolean immediate) {
        DemandKey key = new DemandKey(level, ChunkPos.asLong(x, z));
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        WAITERS.compute(key, (ignored, waiters) -> {
            if (waiters == null) {
                waiters = new CopyOnWriteArrayList<>();
            }
            waiters.add(future);
            return waiters;
        });
        submit(level, chunkMap, key, x, z, immediate);
        return future;
    }

    /** 提交无需等待结果的 chunk 需求。 */
    public static void submit(ServerLevel level, ChunkMap chunkMap, int x, int z, boolean immediate) {
        submit(level, chunkMap, new DemandKey(level, ChunkPos.asLong(x, z)), x, z, immediate);
    }

    private static void submit(ServerLevel level, ChunkMap chunkMap, DemandKey key, int x, int z, boolean immediate) {
        if (immediate) {
            try {
                chunkMap.scheduleGenerationTask(ChunkStatus.FULL, new ChunkPos(x, z));
                return;
            } catch (Throwable t) {
                // 新 chunk 尚无 holder 时 acquireGeneration 会 NPE，降级入队由主线程 drain 走 vanilla 流程
                LOGGER.warn("[chunk-demand] scheduleGenerationTask failed at ({}, {}), falling back to queue:", x, z, t);
            }
        }
        if (!SEEN.add(key)) {
            return;
        }
        while (true) {
            int pending = PENDING_COUNT.get();
            if (pending >= MAX_PENDING) {
                SEEN.remove(key);
                DROPPED.incrementAndGet();
                return;
            }
            if (PENDING_COUNT.compareAndSet(pending, pending + 1)) {
                break;
            }
        }
        PENDING.computeIfAbsent(level, ignored -> new ConcurrentLinkedQueue<>()).add(new Demand(key, new ChunkPos(x, z)));
        QUEUED.incrementAndGet();
    }

    /** 有界等待 chunk 生成完成（毫秒）；超时返回 null（调用方降级空壳）。 */
    public static ChunkAccess await(ServerLevel level, int x, int z, CompletableFuture<ChunkAccess> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            removeWaiter(new DemandKey(level, ChunkPos.asLong(x, z)), future);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            removeWaiter(new DemandKey(level, ChunkPos.asLong(x, z)), future);
            return null;
        } catch (Exception e) {
            removeWaiter(new DemandKey(level, ChunkPos.asLong(x, z)), future);
            return null;
        }
    }

    /** 仅在该 ServerLevel 的 FULL chunk 完成时唤醒等待者。 */
    public static void completeChunk(ServerLevel level, int x, int z, ChunkAccess chunk) {
        DemandKey key = new DemandKey(level, ChunkPos.asLong(x, z));
        List<CompletableFuture<ChunkAccess>> waiters = WAITERS.remove(key);
        if (waiters != null) {
            for (CompletableFuture<ChunkAccess> future : waiters) {
                future.complete(chunk);
            }
        }
        SEEN.remove(key);
        COMPLETED.incrementAndGet();
    }

    /** 当前 ServerLevel 的主线程 drain 取需求；无则返回 null。 */
    public static ChunkPos poll(ServerLevel level) {
        ConcurrentLinkedQueue<Demand> pending = PENDING.get(level);
        if (pending == null) {
            return null;
        }
        Demand demand = pending.poll();
        if (demand == null) {
            PENDING.remove(level, pending);
            return null;
        }
        PENDING_COUNT.decrementAndGet();
        return demand.pos();
    }

    /** drain 结束后调用：当前 ServerLevel 队列清空时重置其去重记录。 */
    public static void afterDrain(ServerLevel level) {
        ConcurrentLinkedQueue<Demand> pending = PENDING.get(level);
        if (pending == null || pending.isEmpty()) {
            if (pending != null) {
                PENDING.remove(level, pending);
            }
            SEEN.removeIf(key -> key.level() == level);
        }
    }

    private static void removeWaiter(DemandKey key, CompletableFuture<ChunkAccess> future) {
        WAITERS.computeIfPresent(key, (ignored, waiters) -> {
            waiters.remove(future);
            return waiters.isEmpty() ? null : waiters;
        });
    }

    /** 当前积压量与累计计数（供日志/运维观测）。 */
    public static String stats() {
        return "queued=" + QUEUED.get() + " dropped=" + DROPPED.get() + " completed=" + COMPLETED.get()
                + " pending=" + PENDING_COUNT.get() + " waiters=" + WAITERS.size();
    }

    private record DemandKey(ServerLevel level, long chunkPos) {
    }

    private record Demand(DemandKey key, ChunkPos pos) {
    }
}
