/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async pathfinding manager: offloads PathFinder A* computation to a small daemon
 * worker pool. The task works on an {@link ImmutablePathNavigationRegion} snapshot
 * and never touches live world state; results are discarded if the navigation went
 * away, is no longer pending, or the result is too old.
 */
public final class AsyncPathfindingManager {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    public static final int MAX_PENDING_TASKS = 1024;
    /** 结果有效窗口（毫秒，真实时钟）：提交到应用超过即超时丢弃，防止生物沿过期路径反向移动。
     * 不可用 tick 数比较——提交端 server.getTickCount()（本次启动以来）与 drain 端
     * level.getGameTime()（世界年龄）基准不同，恒差世界年龄导致全部超时。 */
    public static final long MAX_RESULT_AGE_MILLIS = 3000;
    /** 结果积压硬上限（主队列 + region 桶合计）：防止极端情况下结果堆积导致内存膨胀。 */
    private static final int MAX_RESULT_BACKLOG = 4096;
    /** 低核 VM（如 6 vCPU 生产机）上 2 线程太少，A* 排队超时导致生物不追人；上限防 hjkc 32 核全开。 */
    private static final int WORKER_COUNT = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));

    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicInteger RESULT_BACKLOG = new AtomicInteger();
    private static final AtomicLong LAST_TICK_DRAINED = new AtomicLong(-1);
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();

    // Results submitted by region workers are bucketed per region and drained
    // by that region's worker (same-thread application).
    private static volatile ConcurrentLinkedQueue<Result>[] RESULTS_REGION = new ConcurrentLinkedQueue[0];
    private static volatile AtomicLong[] LAST_REGION_DRAINED = new AtomicLong[0];
    private static final Object REGION_RESULTS_LOCK = new Object();

    // Dimension workers own their level's entity ticks, so their results must be
    // applied by the same dimension worker (never by the main thread).
    private static final ConcurrentHashMap<ServerLevel, DimensionBucket> DIMENSION_RESULTS = new ConcurrentHashMap<>();

    /** Rebuilds the per-region result buckets (startup / auto-scale reconfiguration). */
    public static synchronized void reconfigureRegions(int n) {
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Result>[] buckets = new ConcurrentLinkedQueue[n];
        AtomicLong[] drained = new AtomicLong[n];
        for (int i = 0; i < n; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
            drained[i] = new AtomicLong(-1);
        }
        synchronized (REGION_RESULTS_LOCK) {
            ConcurrentLinkedQueue<Result>[] oldBuckets = RESULTS_REGION;
            for (int i = 0; i < oldBuckets.length; i++) {
                Result result;
                while ((result = oldBuckets[i].poll()) != null) {
                    if (n > 0) buckets[i % n].add(result);
                    else RESULTS.add(result);
                }
            }
            RESULTS_REGION = buckets;
            LAST_REGION_DRAINED = drained;
        }
    }

    // 取消/结果统计埋点 (共享 AsyncTaskStats)。
    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[async-pathfinding]")
            .intervalTicks(600)
            .counter("applied")
            .group("discarded", "dead", "cancelled", "timeout", "saturated", "moved")
            .gauge("pending")
            .build();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(null, r, "PRTS-AsyncPathfinding-" + n.incrementAndGet(), 8L * 1024 * 1024);
            t.setDaemon(true);
            return t;
        }
    });

    private AsyncPathfindingManager() {
    }

    /** 在飞任务超过 worker 数约 2 倍即拒绝新提交（调用方跳过本次寻路，防止排队时间超过结果有效期）。 */
    public static boolean canSubmit() {
        boolean ok = PENDING.get() < Math.max(16, Math.min(64, WORKER_COUNT * 2));
        if (!ok) {
            STATS.increment("discarded.saturated");
        }
        return ok;
    }

    /** One-line status for /servercore status. */
    public static String statusText() {
        return "workers=" + WORKER_COUNT
                + " pending=" + PENDING.get()
                + " applied=" + STATS.counterSum("applied")
                + " discard[dead=" + STATS.counterSum("discarded.dead")
                + " cancelled=" + STATS.counterSum("discarded.cancelled")
                + " timeout=" + STATS.counterSum("discarded.timeout")
                + " saturated=" + STATS.counterSum("discarded.saturated")
                + " moved=" + STATS.counterSum("discarded.moved") + "]";
    }

    private static boolean reservePending() {
        while (true) {
            int current = PENDING.get();
            if (current >= MAX_PENDING_TASKS) return false;
            if (PENDING.compareAndSet(current, current + 1)) {
                STATS.setGauge("pending", current + 1);
                return true;
            }
        }
    }

    private static void releasePending() {
        int pending = PENDING.decrementAndGet();
        STATS.setGauge("pending", pending);
    }

    /**
     * Enqueues a finished result into the bucket owned by the submitting thread:
     * main queue, the owning region's bucket, or the dimension worker's bucket.
     * Returns {@code false} (and does not enqueue) when the combined backlog is
     * over {@link #MAX_RESULT_BACKLOG} — the caller must clear the navigation's
     * pending flag so it can re-submit next tick.
     */
    private static boolean offerResult(int regionId, boolean dimensionOwned, ServerLevel dimensionLevel, Result result) {
        if (RESULT_BACKLOG.incrementAndGet() > MAX_RESULT_BACKLOG) {
            RESULT_BACKLOG.decrementAndGet();
            return false;
        }
        if (dimensionOwned && dimensionLevel != null) {
            DIMENSION_RESULTS.computeIfAbsent(dimensionLevel, ignored -> new DimensionBucket()).queue.add(result);
            return true;
        }
        if (regionId >= 0) {
            synchronized (REGION_RESULTS_LOCK) {
                ConcurrentLinkedQueue<Result>[] buckets = RESULTS_REGION;
                if (regionId < buckets.length) {
                    buckets[regionId].add(result);
                    return true;
                }
            }
            // 桶已重建（自动扩缩容竞态窗口）：回落主队列，结果仍会被 drain。
            RESULTS.add(result);
        } else {
            RESULTS.add(result);
        }
        return true;
    }

    /**
     * Submits an async pathfinding task. The result is routed back to the same
     * ownership class that submitted it: main queue ({@code regionId == -1},
     * {@code dimensionOwned == false}), owning region bucket ({@code regionId >= 0}),
     * or the owning dimension worker's bucket ({@code dimensionOwned == true}).
     */
    public static boolean submit(PathFinder pathFinder, ImmutablePathNavigationRegion snapshot, Mob mob,
                                 Set<BlockPos> targets, float maxRange, int accuracy, float depthMultiplier,
                                 PathNavigation navigation, long serverTick, int regionId, boolean dimensionOwned) {
        if (!reservePending()) return false;
        ServerLevel ownerLevel = dimensionOwned && mob.level() instanceof ServerLevel sl ? sl : null;
        try {
            // 提交时(主线程/区域 worker/维度 worker)捕获实体存活快照: 工作线程的预检只读这个标记,
            // 绝不直接触碰可能已被其他线程修改/回收的原实体字段(内存可见性隔离)。
            java.util.concurrent.atomic.AtomicBoolean aliveSnapshot = new java.util.concurrent.atomic.AtomicBoolean(mob.isAlive());
            long requestNanos = System.nanoTime();
            PathNavigationAccess navigationAccess = (PathNavigationAccess) navigation;
            navigationAccess.arclight$markAsyncPending();
            EXECUTOR.execute(() -> {
                try {
                    // 取消预检(读快照标记): 实体已死 / 导航已取消或失效 → 不计算直接丢弃。
                    if (!aliveSnapshot.get()) {
                        STATS.increment("discarded.dead");
                        return;
                    }
                    if (!navigationAccess.arclight$isAsyncPending()) {
                        STATS.increment("discarded.cancelled");
                        return;
                    }
                    Path path = pathFinder.findPath(snapshot, mob, targets, maxRange, accuracy, depthMultiplier);
                    Result result = new Result(navigation, mob, path, requestNanos, regionId);
                    if (!offerResult(regionId, dimensionOwned, ownerLevel, result)) {
                        // 积压超限：丢弃结果并清 pending，导航下个 tick 可重新提交。
                        ((PathNavigationAccess) navigation).arclight$clearAsyncPending();
                        STATS.increment("discarded.timeout");
                    }
                } catch (Throwable t) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Async pathfinding failed for {}: {}", mob, t.toString());
                    }
                } finally {
                    releasePending();
                }
            });
            return true;
        } catch (Throwable t) {
            ((PathNavigationAccess) navigation).arclight$clearAsyncPending();
            releasePending();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Async pathfinding submission failed for {}: {}", mob, t.toString());
            }
            return false;
        }
    }

    /** Drains pending main-owned results on the main thread (once per tick). */
    public static void drainIfNeeded(long serverTick) {
        long last = LAST_TICK_DRAINED.get();
        if (last == serverTick) return;
        if (RESULTS.isEmpty()) return;
        LAST_TICK_DRAINED.set(serverTick);
        drain(RESULTS, serverTick, Owner.MAIN, null);
        STATS.tick(serverTick);
    }

    /** Drains the given region's result queue on the region worker (same-thread application). */
    public static void drainRegion(int regionId, long serverTick) {
        ConcurrentLinkedQueue<Result>[] buckets = RESULTS_REGION;
        if (regionId < 0 || regionId >= buckets.length) return;
        long last = LAST_REGION_DRAINED[regionId].get();
        if (last == serverTick) return;
        if (buckets[regionId].isEmpty()) return;
        LAST_REGION_DRAINED[regionId].set(serverTick);
        drain(buckets[regionId], serverTick, Owner.REGION, null);
        STATS.tick(serverTick);
    }

    /** Drains the level's result queue on that level's dimension worker before its tick. */
    public static void drainDimension(ServerLevel level, long serverTick) {
        DimensionBucket bucket = DIMENSION_RESULTS.get(level);
        if (bucket == null) {
            return;
        }
        long last = bucket.lastDrained.get();
        if (last == serverTick) return;
        if (bucket.queue.isEmpty()) return;
        bucket.lastDrained.set(serverTick);
        drain(bucket.queue, serverTick, Owner.DIMENSION, level);
        STATS.tick(serverTick);
    }

    private static void drain(ConcurrentLinkedQueue<Result> queue, long serverTick, Owner owner, ServerLevel ownerLevel) {
        List<Result> drained = new ObjectArrayList<>();
        Result r;
        while ((r = queue.poll()) != null) {
            RESULT_BACKLOG.decrementAndGet();
            drained.add(r);
        }
        for (Result result : drained) {
            long ageMillis = (System.nanoTime() - result.requestNanos) / 1_000_000L;
            if (ageMillis > MAX_RESULT_AGE_MILLIS) {
                STATS.increment("discarded.timeout");
                PathNavigation nav = result.navigation.get();
                // 结果作废: 必须清 pending, 否则该导航永久卡死不再提交异步任务。
                if (nav != null) ((PathNavigationAccess) nav).arclight$clearAsyncPending();
                LOGGER.debug("[async-pathfinding] timeout discard: age={}ms", ageMillis);
                continue;
            }
            PathNavigation nav = result.navigation.get();
            if (nav == null) {
                STATS.increment("discarded.dead");
                continue;
            }
            PathNavigationAccess access = (PathNavigationAccess) nav;
            if (!access.arclight$isAsyncPending()) {
                STATS.increment("discarded.cancelled");
                continue;
            }
            // Ownership re-check at apply time: an entity may have crossed a region
            // boundary between submission and result. Never apply navigation state
            // from a thread that no longer owns the entity's region.
            Mob mob = result.mob.get();
            if (mob == null || !mob.isAlive()) {
                STATS.increment("discarded.dead");
                access.arclight$clearAsyncPending();
                continue;
            }
            if (owner == Owner.REGION) {
                int currentRegion = RegionTickManager.currentRegion();
                if (result.regionId != currentRegion || RegionLevel.regionId(mob.blockPosition()) != currentRegion) {
                    STATS.increment("discarded.moved");
                    access.arclight$clearAsyncPending();
                    continue;
                }
            } else if (owner == Owner.DIMENSION && mob.level() != ownerLevel) {
                STATS.increment("discarded.moved");
                access.arclight$clearAsyncPending();
                continue;
            }
            access.arclight$applyAsyncResult(result.path);
            access.arclight$clearAsyncPending();
            STATS.increment("applied");
        }
    }

    private enum Owner {
        MAIN,
        REGION,
        DIMENSION
    }

    private static final class DimensionBucket {
        final ConcurrentLinkedQueue<Result> queue = new ConcurrentLinkedQueue<>();
        final AtomicLong lastDrained = new AtomicLong(-1);
    }

    private static final class Result {
        final java.lang.ref.WeakReference<PathNavigation> navigation;
        final java.lang.ref.WeakReference<Mob> mob;
        final Path path;
        final long requestNanos;
        final int regionId;

        Result(PathNavigation navigation, Mob mob, Path path, long requestNanos, int regionId) {
            this.navigation = new java.lang.ref.WeakReference<>(navigation);
            this.mob = new java.lang.ref.WeakReference<>(mob);
            this.path = path;
            this.requestNanos = requestNanos;
            this.regionId = regionId;
        }
    }
}
