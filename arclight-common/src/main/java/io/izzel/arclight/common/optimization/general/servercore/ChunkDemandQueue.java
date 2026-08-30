/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

/** 统一异步区块需求调度：提交等待、按玩家距离分桶、主线程限量消费。 */
public final class ChunkDemandQueue {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkDemand");

    /** 主线程 tick 处理的 chunk 需求上限（配置 parallel.chunk-demand-per-tick）。 */
    public static volatile int maxPerTick = 50;

    /** 主线程排空最低保证窗口 ms（配置 parallel.chunk-demand-min-drain-ms，0=关闭）。 */
    public static volatile int minDrainMs = 2;

    /** 玩家距离优先级开关（配置 parallel.chunk-demand-player-priority）。 */
    public static volatile boolean playerPriorityEnabled = false;

    /** 低桶队头超过该时长即优先消费（饿死兜底；配置 parallel.chunk-demand-starve-ticks 换算）。 */
    public static volatile long starveNanos = 600L * 50_000_000L;

    /** 硬性上限：超过则丢弃需求，防止队列无界增长导致 OOM。 */
    private static final int MAX_PENDING = 1024;

    /** 待加载需求按 ServerLevel 隔离，防止同坐标跨维度被错误消费；开启优先级时每级 4 桶。 */
    private static final ConcurrentHashMap<ServerLevel, BucketQueues> PENDING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap.KeySetView<DemandKey, Boolean> SEEN = ConcurrentHashMap.newKeySet();

    /** 等待 future 注册表：维度与 chunk 坐标 → 等待者，生成完成后统一 complete。 */
    private static final ConcurrentHashMap<DemandKey, List<CompletableFuture<ChunkAccess>>> WAITERS = new ConcurrentHashMap<>();

    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();

    private ChunkDemandQueue() {
    }

    /** 提交需求并注册等待 future；主线程立即调度，否则入队由 tick 消费。 */
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
        submit(level, chunkMap, key, x, z, immediate, future);
        return future;
    }

    /** 提交无需等待结果的 chunk 需求。 */
    public static void submit(ServerLevel level, ChunkMap chunkMap, int x, int z, boolean immediate) {
        submit(level, chunkMap, new DemandKey(level, ChunkPos.asLong(x, z)), x, z, immediate, null);
    }

    private static void submit(ServerLevel level, ChunkMap chunkMap, DemandKey key, int x, int z,
                               boolean immediate, CompletableFuture<ChunkAccess> waiter) {
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
            ChunkLoadStats.demandDeduped();
            return;
        }
        while (true) {
            int pending = PENDING_COUNT.get();
            if (pending >= MAX_PENDING) {
                SEEN.remove(key);
                DROPPED.incrementAndGet();
                ChunkLoadStats.demandDropped();
                if (waiter != null) {
                    waiter.complete(null);
                    removeWaiter(key, waiter);
                }
                return;
            }
            if (PENDING_COUNT.compareAndSet(pending, pending + 1)) {
                break;
            }
        }
        PENDING.computeIfAbsent(level, ignored -> new BucketQueues())
                .add(new Demand(key, new ChunkPos(x, z), assignBucket(level, x, z), System.nanoTime()));
        QUEUED.incrementAndGet();
        ChunkLoadStats.demandSubmitted();
    }

    /** 按玩家距离分桶；主线程以外提交落最远桶。 */
    private static int assignBucket(ServerLevel level, int x, int z) {
        // 关闭优先级时全部进桶0，poll 也只消费桶0
        if (!playerPriorityEnabled) {
            return 0;
        }
        if (!level.getServer().isSameThread()) {
            return 3;
        }
        int min = Integer.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            int dx = Math.abs(Math.floorDiv(player.getBlockX(), 16) - x);
            int dz = Math.abs(Math.floorDiv(player.getBlockZ(), 16) - z);
            int dist = Math.max(dx, dz);
            if (dist < min) {
                min = dist;
            }
            if (min <= 8) {
                break;
            }
        }
        if (min <= 8) return 0;
        if (min <= 32) return 1;
        if (min <= 128) return 2;
        return 3;
    }

    /** 有界等待 chunk 生成完成（毫秒）；超时返回 null（调用方降级空壳）。 */
    public static ChunkAccess await(ServerLevel level, int x, int z, CompletableFuture<ChunkAccess> future, long timeoutMs) {
        long start = System.nanoTime();
        try {
            ChunkAccess result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            ChunkLoadStats.waitCompleted(System.nanoTime() - start);
            return result;
        } catch (TimeoutException e) {
            removeWaiter(new DemandKey(level, ChunkPos.asLong(x, z)), future);
            ChunkLoadStats.waitTimeout();
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
        ChunkLoadStats.fullCompleted();
    }

    /** 当前 ServerLevel 的主线程 drain 取需求；无则返回 null。 */
    public static ChunkPos poll(ServerLevel level) {
        BucketQueues queues = PENDING.get(level);
        if (queues == null) {
            return null;
        }
        Demand demand;
        if (playerPriorityEnabled) {
            // 饿死兜底：低桶队头超龄即优先消费（有界等待）；仅主线程调用，peek+poll 无并发问题。
            demand = null;
            long now = System.nanoTime();
            for (int b = 1; b <= 3 && demand == null; b++) {
                Demand head = queues.buckets[b].peek();
                if (head != null && now - head.submittedAtNanos() >= starveNanos) {
                    demand = queues.buckets[b].poll();
                    if (demand != null) {
                        ChunkLoadStats.demandStarved();
                    }
                }
            }
            if (demand == null) {
                for (int b = 0; b <= 3 && demand == null; b++) {
                    demand = queues.buckets[b].poll();
                }
            }
            if (demand != null) {
                ChunkLoadStats.demandBucketPolled(demand.bucket());
            }
        } else {
            // 关闭时只使用桶0，行为与改造前纯 FIFO 逐位一致。
            demand = queues.buckets[0].poll();
            if (demand != null) {
                ChunkLoadStats.demandPolled();
            }
        }
        if (demand == null) {
            // 不移除 PENDING：避免与 worker submit 的 check-then-remove 竞态
            return null;
        }
        PENDING_COUNT.decrementAndGet();
        // 消费即清去重：失败的加载允许下一 tick 重新提交，SEEN 不会无限积压
        SEEN.remove(demand.key());
        return demand.pos();
    }

    /** drain 结束后调用：去重随 poll 消费即时清理，这里不再做全局移除（避免与 submit 竞态）。 */
    public static void afterDrain(ServerLevel level) {
        // no-op retained for call-site compatibility
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

    private record Demand(DemandKey key, ChunkPos pos, int bucket, long submittedAtNanos) {
    }

    /** 每 ServerLevel 的 4 桶队列（关闭优先级时只用桶0）。 */
    @SuppressWarnings("unchecked")
    private static final class BucketQueues {
        private final ConcurrentLinkedQueue<Demand>[] buckets = new ConcurrentLinkedQueue[4];

        BucketQueues() {
            for (int i = 0; i < 4; i++) {
                buckets[i] = new ConcurrentLinkedQueue<>();
            }
        }

        void add(Demand demand) {
            buckets[demand.bucket()].add(demand);
        }

        boolean isEmpty() {
            for (ConcurrentLinkedQueue<Demand> bucket : buckets) {
                if (!bucket.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }
}
