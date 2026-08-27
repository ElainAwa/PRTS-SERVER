/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import net.minecraft.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Dimension-level parallelism: runs each dimension's {@link ServerLevel#tick(BooleanSupplier)}
 * on its own worker thread, batched behind a per-tick barrier on the main thread.
 * NeoForge tick pre/post events stay on the main thread; cross-dimension teleports
 * and Bukkit entity events from workers are deferred to the post-barrier main thread.
 */
public final class DimensionTickManager {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    public static final String DIMENSION_THREAD_PREFIX = "PRTS-DimensionTick-";
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, DIMENSION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final ConcurrentLinkedQueue<PendingTransfer> TRANSFERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingEvent> PENDING_EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean IN_DIMENSION_TICK = new AtomicBoolean(false);

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[dimension-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("pendingTransfer", "player", "entity", "cancelled", "dropped")
            // B 组(2026-08-27 docs/2026-08-27-region-parallel-barrier-idle-fix.md §2):
            // 维度 barrier 软降级遥测:softDegrades = 触发次数,lateUnits = 未完成的 playerless 维度数。
            .counter("barrier.softDegrades")
            .counter("barrier.lateUnits")
            .timer("overworld").timer("nether").timer("end").timer("other")
            .build();

    // A1': 硬超时降级跟踪。degraded 维度不再进 worker 池,由主线程串行 tick,
    // 连续正常 recover-ticks 后自动恢复并行(crash 模式不启用)。
    private static final Map<ResourceKey<Level>, AtomicInteger> DEGRADED_DIMENSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger HARD_TIMEOUTS = new AtomicInteger();

    static boolean isDegradedDimension(ResourceKey<Level> dim) {
        return DEGRADED_DIMENSIONS.containsKey(dim);
    }

    private static void markDegradedDimension(ResourceKey<Level> dim) {
        DEGRADED_DIMENSIONS.computeIfAbsent(dim, k -> new AtomicInteger()).set(0);
    }

    /** 串行 tick 期间每 tick 调用:累计正常 tick,达阈值解除降级。 */
    private static void tickDimensionRecovery(ResourceKey<Level> dim) {
        AtomicInteger counter = DEGRADED_DIMENSIONS.get(dim);
        if (counter != null && counter.incrementAndGet() >= PRTSFeaturesConfig.barrierTimeoutRecoverTicks) {
            DEGRADED_DIMENSIONS.remove(dim, counter);
            LOGGER.info("[PRTS-Barrier] dimension {} recovered from degraded mode", dim.location());
        }
    }

    /** /servercore status Barrier 行(含 region 侧)。 */
    public static String barrierStatusText() {
        StringBuilder degraded = new StringBuilder("[");
        for (ResourceKey<Level> dim : DEGRADED_DIMENSIONS.keySet()) {
            if (degraded.length() > 1) {
                degraded.append(',');
            }
            degraded.append(dim.location());
        }
        degraded.append(']');
        return "hardTimeouts=" + HARD_TIMEOUTS.get() + " degraded=" + degraded
                + " action=" + PRTSFeaturesConfig.barrierTimeoutAction.name().toLowerCase()
                + " | region: " + RegionTickManager.regionBarrierStatusText();
    }

    @FunctionalInterface
    public interface SyncTime {
        void sync(ServerLevel level);
    }

    /**
     * NeoForge level tick event bridge (fireLevelTickPre/Post). The common module has
     * no NeoForge API on its compile classpath, so the platform layer registers the
     * real EventHooks callbacks at mod load.
     */
    @FunctionalInterface
    public interface LevelTickCallback {
        void fire(ServerLevel level, BooleanSupplier hasTimeLeft);
    }

    private static volatile LevelTickCallback PRE = (level, hasTimeLeft) -> {
    };
    private static volatile LevelTickCallback POST = (level, hasTimeLeft) -> {
    };

    /** Register the platform level-tick event dispatchers (called from the NeoForge mod init). */
    public static void setLevelTickCallbacks(LevelTickCallback pre, LevelTickCallback post) {
        PRE = pre;
        POST = post;
    }

    private DimensionTickManager() {
    }

    /** True while the parallel dimension ticks are running (worker threads). */
    public static boolean inDimensionTick() {
        return IN_DIMENSION_TICK.get();
    }

    /** True on a dimension tick worker thread. */
    public static boolean isDimensionTickThread() {
        return Thread.currentThread().getName().startsWith(DIMENSION_THREAD_PREFIX);
    }

    /** Defers a cross-dimension teleport from a worker thread to the post-barrier main thread. */
    public static void enqueueTransfer(Entity entity, DimensionTransition transition) {
        TRANSFERS.add(new PendingTransfer(entity, transition));
    }

    /**
     * Defers an entity load/unload event from a worker thread: Bukkit events must
     * fire on the main thread (SimplePluginManager.callEvent enforces it).
     */
    public static void enqueueEntitiesEvent(ServerLevel level, ChunkPos chunkPos, List<Entity> entities, boolean unload) {
        PENDING_EVENTS.add(new PendingEvent(level, chunkPos, entities, unload));
    }

    /**
     * Executes the complete tick phase on the main thread, replacing the vanilla
     * sequential loop (called from MinecraftServerMixin_DimParallel when enabled).
     *
     * @param server              the server instance
     * @param units               tick units (dimensions)
     * @param hasTimeLeft         the tickChildren BooleanSupplier
     * @param tickCount           current server tick count
     * @param perWorldTickTimes   the server's {@code perWorldTickTimes} map (vanilla format)
     * @param syncTime            {@code MinecraftServer.synchronizeTime} bridge
     */
    public static void parallelTick(MinecraftServer server, ParallelTickUnit[] units, BooleanSupplier hasTimeLeft,
                                    int tickCount, Map<ResourceKey<Level>, long[]> perWorldTickTimes,
                                    SyncTime syncTime) {
        int n = units.length;
        STATS.increment("ticks");

        // 1. Pre phase (main thread, vanilla per-unit order).
        //    synchronizeTime runs inside the vanilla loop per unit; keep it here.
        long[] startNanos = new long[n];
        for (int i = 0; i < n; i++) {
            ServerLevel level = units[i].level();
            if (tickCount % 20 == 0) {
                syncTime.sync(level);
            }
            startNanos[i] = Util.getNanos();
            PRE.fire(level, hasTimeLeft);
            // B4: 在 belt BE tick 之前应用延迟的 passenger 注册，保证 counter 及时刷新
            RegionTickManager.drainBeltPassengers(level);
            RegionTickManager.drainMainThreadBlockEntities(level);
            ((io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheDemandBridge)
                    (Object) level.getChunkSource()).arclight$drainChunkDemands();
        }

        // 2. Parallel ticks: dimensions with players stay on the main thread to
        //    keep vanilla single-thread semantics for player tick (container menus,
        //    network packets, Bukkit player events); playerless dimensions run on
        //    workers behind a per-tick barrier.
        IN_DIMENSION_TICK.set(true);
        try {
            java.util.ArrayList<ParallelTickUnit> playerless = new java.util.ArrayList<>();
            java.util.ArrayList<ParallelTickUnit> withPlayers = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (units[i].level().players().isEmpty()) {
                    playerless.add(units[i]);
                } else {
                    withPlayers.add(units[i]);
                }
            }
            // A1': degraded 维度不进 worker 池,主线程串行 tick(计数恢复进度)。
            java.util.ArrayList<ParallelTickUnit> degraded = new java.util.ArrayList<>();
            java.util.ArrayList<ParallelTickUnit> parallelDims = new java.util.ArrayList<>();
            for (ParallelTickUnit unit : playerless) {
                if (isDegradedDimension(unit.level().dimension())) {
                    degraded.add(unit);
                } else {
                    parallelDims.add(unit);
                }
            }
            CountDownLatch latch = new CountDownLatch(parallelDims.size());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // B 组:本会话软降级门,发送后 playerless 维度的 vanilla hasTimeLeft 短路见
            // awaitDimensionBarrier 说明;仅影响被软降级的维度 worker。
            AtomicBoolean sessionDegrade = new AtomicBoolean(false);
            for (final ParallelTickUnit unit : parallelDims) {
                try {
                    POOL.execute(() -> {
                        try {
                            // B 组:软降级时让 vanilla 的 hasTimeLeft 在维度 worker 上短路,
                            // 让 ServerLevel.tick 的分块刻尽早让出,加快 wind-down。
                            BooleanSupplier unitTimeLeft =
                                    () -> !sessionDegrade.get() && hasTimeLeft.getAsBoolean();
                            unit.tick(unitTimeLeft);
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        } finally {
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    // RejectedExecutionException (pool saturated) etc.: count the unit
                    // as done so the barrier never hangs, then surface the failure.
                    failure.compareAndSet(null, t);
                    latch.countDown();
                }
            }
            for (ParallelTickUnit unit : withPlayers) {
                try {
                    unit.tick(hasTimeLeft);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
            // A1': degraded 维度主线程串行 tick(与 withPlayers 同路径,POST drains 正常覆盖)。
            for (ParallelTickUnit unit : degraded) {
                try {
                    tickDimensionRecovery(unit.level().dimension());
                    unit.tick(hasTimeLeft);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
            try {
                java.util.ArrayList<ResourceKey<Level>> dims = new java.util.ArrayList<>(parallelDims.size());
                for (ParallelTickUnit unit : parallelDims) {
                    dims.add(unit.level().dimension());
                }
                awaitDimensionBarrier(latch, server, sessionDegrade, dims);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for parallel ticks", e);
            }

            // 3. Propagate a worker failure (crashes the server like vanilla would).
            Throwable failure0 = failure.get();
            if (failure0 != null) {
                throw new ReportedExceptionWrapping(failure0);
            }

            // 4. Post phase (main thread) + perWorldTickTimes in vanilla format.
            for (int i = 0; i < n; i++) {
                ServerLevel level = units[i].level();
                // worker 触发的实体新增（掉落/蛋/投射物/XP）必须在主线程 addEntity，
                // 排在所有 drain 之前，让同 tick 后续逻辑能看见新实体。
                RegionTickManager.drainMainThreadEntityAdds(level);
                // 维度 worker 收集的方块 tick 在主线程执行（getBlockEntity 非主线程恒 null，比较器等 BE 依赖逻辑失效）
                RegionTickManager.drainMainThreadBlockTicks(level);
                // 维度 worker 收集的方块实体 tick 在主线程执行（BE tick 依赖主线程 Bukkit API）
                RegionTickManager.drainMainThreadBlockEntityTicks(level);
                // 维度 worker 收集的装置实体 tick 在主线程执行（getBlockEntity 非主线程固定为 null）
                RegionTickManager.drainMainThreadEntityTicks(level);
                POST.fire(level, hasTimeLeft);
                long elapsed = Util.getNanos() - startNanos[i];
                perWorldTickTimes.computeIfAbsent(level.dimension(), k -> new long[100])[tickCount % 100] = elapsed;
                STATS.record(timerName(level.dimension()), elapsed);
            }

            // 5. Execute deferred cross-dimension transfers on the main thread.
            drainTransfers();

            // 6. Fire deferred entity load/unload events on the main thread.
            drainEvents();
        } finally {
            IN_DIMENSION_TICK.set(false);
        }

        STATS.tick(tickCount);
    }

    /**
     * B 组:维度 barrier 等待(时间切片 join,docs/2026-08-27-region-parallel-barrier-idle-fix.md §2)。
     *
     * <p>与 region 侧同款预算制:主线程正常时完整等待(barrierTimeoutMs 硬超时 + dump);
     * 已超预算且 soft-degrade 开启时以剩余预算等待,超时把 playerless 维度的 hasTimeLeft
     * 短路并记 {@code barrier.lateUnits}(droppedRegion 遥测)。维度 worker 跑的是整段
     * ServerLevel.tick,没有像 region 那样可按实体/BE/方块刻退出的合作点,因此 wind-down
     * 超限后仍须回硬等待——绝不带着在会话内的维度 worker 进入 POST phase(同 chunk tick
     * 责任唯一不可破坏)。ENFORCE 语义不破坏:worker 异常仍由 {@link #parallelTick} 上抛。</p>
     */
    private static void awaitDimensionBarrier(CountDownLatch latch, MinecraftServer server,
                                              AtomicBoolean sessionDegrade,
                                              java.util.List<ResourceKey<Level>> playerlessDims) throws InterruptedException {
        long budgetMs = PRTSFeaturesConfig.barrierSoftDegrade ? RegionTickManager.barrierBudgetMs(server) : -1L;
        long wallStart = Util.getNanos();
        if (budgetMs < 0L) {
            if (!hardAwaitDimension(latch, playerlessDims, "dimension")) {
                throw new RuntimeException(barrierTimeoutDump("dimension"));
            }
            return;
        }
        if (latch.await(budgetMs, TimeUnit.MILLISECONDS)) {
            return;
        }
        // 软降级:短路 playerless 维度 worker 的 hasTimeLeft,让其分块刻尽早让出。
        sessionDegrade.set(true);
        long late = latch.getCount();
        STATS.increment("barrier.softDegrades");
        STATS.add("barrier.lateUnits", late);
        if (latch.await(RegionTickManager.BARRIER_WIND_DOWN_MS, TimeUnit.MILLISECONDS)) {
            return;
        }
        LOGGER.warn("[PRTS-Barrier] soft-degrade: {} dimension(s) not winding down within {}ms "
                + "(budget={}ms); awaiting barrier timeout to preserve single-writer semantics",
                late, RegionTickManager.BARRIER_WIND_DOWN_MS, budgetMs);
        long hardRemainingMs = PRTSFeaturesConfig.barrierTimeoutMs - ((Util.getNanos() - wallStart) / 1_000_000L);
        if (!hardAwaitDimension(latch, playerlessDims, "dimension", Math.max(1L, hardRemainingMs))) {
            throw new RuntimeException(barrierTimeoutDump("dimension"));
        }
    }

    /**
     * A1': 维度硬超时等待 + degrade 处理。返回 true=已完成;false=触发 crash 条件(dump 已记)。
     * crash 模式:超时即返回 false(调用方抛 dump 崩服)。
     * degrade 模式:记 dump 日志 + 标记全部 playerless 维度 degraded + 再等一个完整窗口,
     * 仍卡死才返回 false——绝不带着在跑的维度 worker 进入 POST phase(单写者语义)。
     */
    private static boolean hardAwaitDimension(CountDownLatch latch, java.util.List<ResourceKey<Level>> playerlessDims,
                                              String where) throws InterruptedException {
        return hardAwaitDimension(latch, playerlessDims, where, PRTSFeaturesConfig.barrierTimeoutMs);
    }

    private static boolean hardAwaitDimension(CountDownLatch latch, java.util.List<ResourceKey<Level>> playerlessDims,
                                              String where, long waitMs) throws InterruptedException {
        if (latch.await(waitMs, TimeUnit.MILLISECONDS)) {
            return true;
        }
        if (PRTSFeaturesConfig.barrierTimeoutAction == PRTSFeaturesConfig.BarrierTimeoutAction.CRASH) {
            return false;
        }
        HARD_TIMEOUTS.incrementAndGet();
        barrierTimeoutDump(where);
        LOGGER.error("[PRTS-Barrier] hard timeout ({}) with degrade action: marking {} dimension(s) degraded, "
                + "waiting one more window before giving up", where, playerlessDims.size());
        for (ResourceKey<Level> dim : playerlessDims) {
            markDegradedDimension(dim);
        }
        return latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Barrier timeout diagnostic: dump all threads and return the crash message. */
    static String barrierTimeoutDump(String where) {
        StringBuilder sb = new StringBuilder("PRTS barrier timeout in ").append(where)
                .append(" after ").append(PRTSFeaturesConfig.barrierTimeoutMs).append("ms");
        LOGGER.error("[PRTS-Barrier] {}", sb);
        for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            sb.append('\n').append(ti);
        }
        return sb.toString();
    }

    private static void drainTransfers() {
        PendingTransfer pt;
        while ((pt = TRANSFERS.poll()) != null) {
            Entity entity = pt.entity.get();
            if (entity == null || entity.isRemoved()) {
                STATS.increment("pendingTransfer.dropped");
                continue;
            }
            // 玩家等待传送期间离线: connection 解绑则取消传送(正常玩家 connection 永不为 null)。
            if (entity instanceof ServerPlayer player && player.connection == null) {
                LOGGER.info("[dimension-tick] pending transfer cancelled (player offline): {}", player);
                STATS.increment("pendingTransfer.cancelled");
                continue;
            }
            try {
                entity.changeDimension(pt.transition);
                STATS.increment(entity instanceof ServerPlayer
                        ? "pendingTransfer.player" : "pendingTransfer.entity");
            } catch (Throwable t) {
                LOGGER.warn("[dimension-tick] pending transfer failed for {}: {}", entity, t.toString());
                STATS.increment("pendingTransfer.dropped");
            }
        }
    }

    private static void drainEvents() {
        PendingEvent pe;
        while ((pe = PENDING_EVENTS.poll()) != null) {
            try {
                if (pe.unload) {
                    CraftEventFactory.callEntitiesUnloadEvent(pe.level, pe.chunkPos, pe.entities);
                } else {
                    CraftEventFactory.callEntitiesLoadEvent(pe.level, pe.chunkPos, pe.entities);
                }
            } catch (Throwable t) {
                LOGGER.warn("[dimension-tick] deferred entity event failed for {}: {}", pe.chunkPos, t.toString());
            }
        }
    }

    private static String timerName(ResourceKey<Level> dimension) {
        String path = dimension.location().getPath();
        switch (path) {
            case "overworld":
                return "overworld";
            case "the_nether":
                return "nether";
            case "the_end":
                return "end";
            default:
                return "other";
        }
    }

    /** Wrap a worker failure without losing the original cause. */
    private static final class ReportedExceptionWrapping extends RuntimeException {
        ReportedExceptionWrapping(Throwable cause) {
            super("Exception ticking world on dimension thread", cause);
        }
    }

    private record PendingTransfer(WeakReference<Entity> entity, DimensionTransition transition) {
        PendingTransfer(Entity entity, DimensionTransition transition) {
            this(new WeakReference<>(entity), transition);
        }
    }

    private record PendingEvent(ServerLevel level, ChunkPos chunkPos, List<Entity> entities, boolean unload) {
    }
}
