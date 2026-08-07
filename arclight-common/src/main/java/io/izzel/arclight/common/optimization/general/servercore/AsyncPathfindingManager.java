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
 * PRTS async pathfinding manager (P1 experiment, AI-created).
 * Offloads PathFinder A* computation to a small daemon worker pool.
 *
 * Thread-safety contract:
 * - The task receives an {@link ImmutablePathNavigationRegion} (block-state
 *   reference snapshot captured on the main thread) and a task-private
 *   PathFinder; the worker never touches live Level/LevelChunk state.
 * - Cancellation is best-effort by design (A* has no preemption point):
 *   a pre-check runs before the computation (navigation no longer pending,
 *   mob dead) and the result is discarded at drain time when the navigation
 *   went away, is not pending anymore, or the result is too old.
 *
 * Monitoring: counters/groups/gauges are provided by the shared
 * {@link AsyncTaskStats} instance (same log format as the P1 smoke runs).
 */
public final class AsyncPathfindingManager {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    public static final int MAX_PENDING_TASKS = 1024;
    public static final long MAX_RESULT_AGE_TICKS = 20;
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicLong LAST_TICK_DRAINED = new AtomicLong(-1);
    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();

    // 取消/结果统计埋点 (共享 AsyncTaskStats, P2/P3 同步协议复用同一可观测面)。
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

    public static boolean submit(PathFinder pathFinder, ImmutablePathNavigationRegion snapshot, Mob mob,
                                 Set<BlockPos> targets, float maxRange, int accuracy, float depthMultiplier,
                                 PathNavigation navigation, long serverTick) {
        if (!canSubmit()) return false;
        PENDING.incrementAndGet();
        STATS.setGauge("pending", PENDING.get());
        // 提交时(主线程)捕获实体存活快照: 工作线程的预检只读这个标记,
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
                RESULTS.add(new Result(navigation, path, serverTick));
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

    /**
     * Called on the main thread whenever a pathfinding request happens and there
     * are pending results; drains the result queue and applies fresh results.
     */
    public static void drainIfNeeded(long serverTick) {
        long last = LAST_TICK_DRAINED.get();
        if (last == serverTick) return;
        if (RESULTS.isEmpty()) return;
        LAST_TICK_DRAINED.set(serverTick);
        drain(serverTick);
        STATS.tick(serverTick);
    }

    private static void drain(long serverTick) {
        List<Result> drained = new ObjectArrayList<>();
        Result r;
        while ((r = RESULTS.poll()) != null) drained.add(r);
        for (Result result : drained) {
            if (serverTick - result.requestTick > MAX_RESULT_AGE_TICKS) {
                STATS.increment("discarded.timeout");
                PathNavigation nav = result.navigation.get();
                // 结果作废: 必须清 pending, 否则该导航永久卡死不再提交异步任务。
                if (nav != null) ((PathNavigationAccess) nav).arclight$clearAsyncPending();
                LOGGER.info("[async-pathfinding] timeout discard: delta={} requestTick={} serverTick={}",
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
