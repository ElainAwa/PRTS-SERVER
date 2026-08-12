/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Set;
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
    public static final long MAX_RESULT_AGE_TICKS = 20;
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicLong LAST_TICK_DRAINED = new AtomicLong(-1);
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();

    // Results submitted by region workers are bucketed per region and drained
    // by that region's worker (same-thread application).
    private static volatile ConcurrentLinkedQueue<Result>[] RESULTS_REGION = new ConcurrentLinkedQueue[0];
    private static volatile AtomicLong[] LAST_REGION_DRAINED = new AtomicLong[0];

    /** Rebuilds the per-region result buckets (startup / auto-scale reconfiguration). */
    public static synchronized void reconfigureRegions(int n) {
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Result>[] buckets = new ConcurrentLinkedQueue[n];
        AtomicLong[] drained = new AtomicLong[n];
        for (int i = 0; i < n; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
            drained[i] = new AtomicLong(-1);
        }
        RESULTS_REGION = buckets;
        LAST_REGION_DRAINED = drained;
    }

    // 取消/结果统计埋点 (共享 AsyncTaskStats)。
    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[async-pathfinding]")
            .intervalTicks(600)
            .counter("applied")
            .group("discarded", "dead", "cancelled", "timeout")
            .gauge("pending")
            .build();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(WORKER_COUNT, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "PRTS-AsyncPathfinding-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private AsyncPathfindingManager() {
    }

    public static boolean canSubmit() {
        return PENDING.get() < MAX_PENDING_TASKS;
    }

    /**
     * Submits an async pathfinding task. {@code regionId} routes the result to the
     * main queue ({@code -1}) or the owning region's queue ({@code >= 0}).
     */
    public static boolean submit(PathFinder pathFinder, ImmutablePathNavigationRegion snapshot, Mob mob,
                                 Set<BlockPos> targets, float maxRange, int accuracy, float depthMultiplier,
                                 PathNavigation navigation, long serverTick, int regionId) {
        if (!canSubmit()) return false;
        PENDING.incrementAndGet();
        STATS.setGauge("pending", PENDING.get());
        // 提交时(主线程/区域 worker)捕获实体存活快照: 工作线程的预检只读这个标记,
        // 绝不直接触碰可能已被主线程修改/回收的原实体字段(内存可见性隔离)。
        java.util.concurrent.atomic.AtomicBoolean aliveSnapshot = new java.util.concurrent.atomic.AtomicBoolean(mob.isAlive());
        EXECUTOR.execute(() -> {
            try {
                // 取消预检(读快照标记): 实体已死 / 导航已取消或失效 → 不计算直接丢弃。
                if (!aliveSnapshot.get()) {
                    STATS.increment("discarded.dead");
                    return;
                }
                if (!((PathNavigationAccess) navigation).arclight$isAsyncPending()) {
                    STATS.increment("discarded.cancelled");
                    return;
                }
                Path path = pathFinder.findPath(snapshot, mob, targets, maxRange, accuracy, depthMultiplier);
                ConcurrentLinkedQueue<Result>[] buckets = RESULTS_REGION;
                if (regionId >= 0 && regionId < buckets.length) {
                    buckets[regionId].add(new Result(navigation, path, serverTick));
                } else {
                    RESULTS.add(new Result(navigation, path, serverTick));
                }
            } catch (Throwable t) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Async pathfinding failed for {}: {}", mob, t.toString());
                }
            } finally {
                PENDING.decrementAndGet();
                STATS.setGauge("pending", PENDING.get());
            }
        });
        return true;
    }

    /** Drains pending results on the main thread (once per tick). */
    public static void drainIfNeeded(long serverTick) {
        long last = LAST_TICK_DRAINED.get();
        if (last == serverTick) return;
        if (RESULTS.isEmpty()) return;
        LAST_TICK_DRAINED.set(serverTick);
        drain(RESULTS, serverTick);
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
        drain(buckets[regionId], serverTick);
        STATS.tick(serverTick);
    }

    private static void drain(ConcurrentLinkedQueue<Result> queue, long serverTick) {
        List<Result> drained = new ObjectArrayList<>();
        Result r;
        while ((r = queue.poll()) != null) drained.add(r);
        for (Result result : drained) {
            if (serverTick - result.requestTick > MAX_RESULT_AGE_TICKS) {
                STATS.increment("discarded.timeout");
                PathNavigation nav = result.navigation.get();
                // 结果作废: 必须清 pending, 否则该导航永久卡死不再提交异步任务。
                if (nav != null) ((PathNavigationAccess) nav).arclight$clearAsyncPending();
                LOGGER.debug("[async-pathfinding] timeout discard: delta={} requestTick={} serverTick={}",
                        serverTick - result.requestTick, result.requestTick, serverTick);
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
            access.arclight$applyAsyncResult(result.path);
            access.arclight$clearAsyncPending();
            STATS.increment("applied");
        }
    }

    private static final class Result {
        final java.lang.ref.WeakReference<PathNavigation> navigation;
        final Path path;
        final long requestTick;

        Result(PathNavigation navigation, Path path, long requestTick) {
            this.navigation = new java.lang.ref.WeakReference<>(navigation);
            this.path = path;
            this.requestTick = requestTick;
        }
    }
}
