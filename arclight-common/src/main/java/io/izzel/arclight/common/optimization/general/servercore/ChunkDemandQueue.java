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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** 待加载需求队列（worker/主线程提交，生成调度消费）。 */
    private static final ConcurrentLinkedQueue<ChunkPos> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<Long> SEEN = ConcurrentHashMap.newKeySet();

    /** 等待 future 注册表：chunk 坐标 → 等待者，生成完成后统一 complete。 */
    private static final ConcurrentHashMap<Long, List<CompletableFuture<ChunkAccess>>> WAITERS = new ConcurrentHashMap<>();

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
        long key = ChunkPos.asLong(x, z);
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        WAITERS.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(future);
        if (immediate) {
            try {
                chunkMap.scheduleGenerationTask(ChunkStatus.FULL, new ChunkPos(x, z));
            } catch (Throwable t) {
                LOGGER.warn("[chunk-demand] scheduleGenerationTask failed at ({}, {}): {}", x, z, t.toString());
            }
        } else if (SEEN.add(key) && PENDING.size() < MAX_PENDING) {
            PENDING.add(new ChunkPos(x, z));
            QUEUED.incrementAndGet();
        }
        return future;
    }

    /** 有界等待 chunk 生成完成（毫秒）；超时返回 null（调用方降级空壳）。 */
    public static ChunkAccess await(CompletableFuture<ChunkAccess> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成完成通知：complete 所有等待该 chunk 的 future，并清除需求记录。 */
    public static void completeChunk(int x, int z, ChunkAccess chunk) {
        long key = ChunkPos.asLong(x, z);
        List<CompletableFuture<ChunkAccess>> waiters = WAITERS.remove(key);
        if (waiters != null) {
            for (CompletableFuture<ChunkAccess> f : waiters) {
                f.complete(chunk);
            }
        }
        SEEN.remove(key);
        COMPLETED.incrementAndGet();
    }

    /** 主线程 drain 取需求；无则返回 null。 */
    public static ChunkPos poll() {
        return PENDING.poll();
    }

    /** drain 结束后调用：队列清空时重置去重集合。 */
    public static void afterDrain() {
        if (PENDING.isEmpty()) {
            SEEN.clear();
        }
    }

    /** 当前积压量与累计计数（供日志/运维观测）。 */
    public static String stats() {
        return "queued=" + QUEUED.get() + " dropped=" + DROPPED.get() + " completed=" + COMPLETED.get()
                + " pending=" + PENDING.size() + " waiters=" + WAITERS.size();
    }
}
