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
import java.util.Queue;
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
                Thread t = new Thread(null, r, DIMENSION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet(), 8L * 1024 * 1024);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final ConcurrentLinkedQueue<PendingTransfer> TRANSFERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingEvent> PENDING_EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean IN_DIMENSION_TICK = new AtomicBoolean(false);

    // 预算自适应（总控闭环）：主线程按滚动平均 tick 时长压缩 worker 时间片，
    // 落后越多压得越狠（下限 50%），恢复正常后每 tick 回升 5% 直到全速。
    private static final double[] TICK_MS_RING = new double[100];
    private static int tickMsIndex = 0;
    private static volatile double workerSliceScale = 1.0;

    /** 当前 worker 时间片缩放系数（region/dimension 共享）。 */
    public static double currentSliceScale() {
        return workerSliceScale;
    }

    private static void updateSliceScale(double tickMs) {
        TICK_MS_RING[tickMsIndex++ % TICK_MS_RING.length] = tickMs;
        double sum = 0.0;
        for (double v : TICK_MS_RING) {
            sum += v;
        }
        double avg = sum / TICK_MS_RING.length;
        if (avg > 45.0) {
            workerSliceScale = Math.max(0.5, 1.0 - (avg - 45.0) / 100.0);
        } else {
            workerSliceScale = Math.min(1.0, workerSliceScale + 0.05);
        }
    }

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[dimension-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("pendingTransfer", "player", "entity", "cancelled", "dropped")
            // B 组(2026-08-27 docs/2026-08-27-region-parallel-barrier-idle-fix.md §2):
            // 维度 barrier 软降级遥测:softDegrades = 触发次数,lateUnits = 未完成的 playerless 维度数。
            .counter("barrier.softDegrades")
            .counter("barrier.lateUnits")
            .timer("overworld").timer("nether").timer("end").timer("other")
            // 调度器阶段遥测（主线程=调度器）：barrier 等待 / POST 回收 / worker 会话。
            .timer("barrier.wait").timer("post.drain").timer("worker.session")
            .build();

    // 硬超时降级跟踪:degraded 维度由主线程串行 tick,连续正常 recover-ticks 后自动恢复并行。
    private static final Map<ResourceKey<Level>, AtomicInteger> DEGRADED_DIMENSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger HARD_TIMEOUTS = new AtomicInteger();

    // 整服熔断:连续 3 次 barrier 硬超时触发,全部维度主线程串行 + region 并行全关(重启恢复)。
    private static final AtomicInteger CONSECUTIVE_HARD_TIMEOUTS = new AtomicInteger();
    private static volatile boolean FAULT_FALLBACK = false;
    private static volatile String FAULT_FALLBACK_REASON = "";

    /** 整服熔断是否激活(regionEnabled() 与 parallelTick 都查询)。 */
    public static boolean isFaultFallback() {
        return FAULT_FALLBACK;
    }

    static String faultFallbackText() {
        return FAULT_FALLBACK ? "ON(" + FAULT_FALLBACK_REASON + ")" : "off";
    }

    /** 每次硬超时(维度/region)调用;累计达阈值触发整服熔断。 */
    static void onHardTimeout(String where) {
        HARD_TIMEOUTS.incrementAndGet();
        if (FAULT_FALLBACK || !PRTSFeaturesConfig.onFaultFallbackVanilla) {
            return;
        }
        int n = CONSECUTIVE_HARD_TIMEOUTS.incrementAndGet();
        if (n >= 3) {
            FAULT_FALLBACK = true;
            FAULT_FALLBACK_REASON = "3 consecutive barrier hard timeouts (last: " + where + ")";
            LOGGER.error("[PRTS-Barrier] FAULT FALLBACK ACTIVATED: {}", FAULT_FALLBACK_REASON);
        }
    }

    /** 每 tick 开始调用:清零连续超时计数(未超时的 tick 即打断连续性)。 */
    private static void resetHardTimeoutStreak() {
        if (!FAULT_FALLBACK) {
            CONSECUTIVE_HARD_TIMEOUTS.set(0);
        }
    }

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
                + " faultFallback=" + faultFallbackText()
                + " | region: " + RegionTickManager.regionBarrierStatusText();
    }

    // 每维度 tick 耗时环形缓冲(100 tick),供 status DimensionTps 行换算 TPS。
    private static final Map<ResourceKey<Level>, long[]> DIM_TICK_TIMES = new ConcurrentHashMap<>();

    /** POST 阶段记录本维度 tick 耗时(nanos),环形 100 槽。 */
    static void recordDimensionTickTime(ResourceKey<Level> dim, int tickCount, long elapsedNanos) {
        long[] ring = DIM_TICK_TIMES.computeIfAbsent(dim, k -> new long[100]);
        ring[tickCount % 100] = elapsedNanos;
    }

    /** /servercore status DimensionTps 行:每维度 1000/平均 MSPT,无样本显示 20.0。 */
    public static String dimensionTpsText() {
        StringBuilder sb = new StringBuilder();
        DIM_TICK_TIMES.forEach((dim, ring) -> {
            long sum = 0L;
            int n = 0;
            for (long v : ring) {
                if (v > 0L) {
                    sum += v;
                    n++;
                }
            }
            double mspt = n > 0 ? sum / (double) n / 1_000_000.0 : 0.0;
            double tps = mspt <= 0.0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(dim.location().getPath()).append('=').append(String.format("%.1f", tps));
        });
        return sb.length() == 0 ? "(no samples)" : sb.toString();
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

    // 维度 worker 收集的客户端同步链任务（ChunkMap 广播/实体管理器），POST 段主线程执行。
    private static final Map<ServerLevel, Queue<Runnable>> POST_SYNC = new ConcurrentHashMap<>();

    /** 收集维度 worker 上的同步链任务，POST 段主线程 drain（保持 ChunkMap 增量广播链完整）。 */
    public static void collectPostSync(ServerLevel level, Runnable task) {
        POST_SYNC.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(task);
    }

    /** POST 段（主线程）执行本 tick 收集的同步链任务，按 vanilla 顺序。 */
    public static void drainPostSync(ServerLevel level) {
        Queue<Runnable> q = POST_SYNC.remove(level);
        if (q != null) {
            Runnable t;
            while ((t = q.poll()) != null) {
                t.run();
            }
        }
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
        long tickStartNanos = Util.getNanos();

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
        resetHardTimeoutStreak();
        IN_DIMENSION_TICK.set(true);
        try {
            // 整服熔断:所有维度主线程串行 tick(vanilla 语义,无 worker/barrier)。
            if (FAULT_FALLBACK) {
                for (int i = 0; i < n; i++) {
                    try {
                        units[i].tick(hasTimeLeft);
                    } catch (Throwable t) {
                        LOGGER.error("[PRTS-Barrier] fault-fallback serial tick failed for {}: {}",
                                units[i].level().dimension().location(), t.toString());
                    }
                }
                return;
            }
            // 有玩家维度也提交 worker 池：维度 tick 串行骨架（tickChunks/方块刻/dispatch）
            // 在 worker 上并行，玩家实体 tick 由 dispatchAndTick 路由主线程 POST drain（见
            // RegionTickManager.dispatchAndTick），玩家连接/网络包仍在主线程 tickChildren。
            java.util.ArrayList<ParallelTickUnit> withPlayers = new java.util.ArrayList<>();
            java.util.ArrayList<ParallelTickUnit> degraded = new java.util.ArrayList<>();
            java.util.ArrayList<ParallelTickUnit> parallelDims = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                ParallelTickUnit unit = units[i];
                if (isDegradedDimension(unit.level().dimension())) {
                    degraded.add(unit);
                } else {
                    parallelDims.add(unit);
                    if (!unit.level().players().isEmpty()) {
                        withPlayers.add(unit);
                    }
                }
            }
            CountDownLatch latch = new CountDownLatch(parallelDims.size());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // B 组:本会话软降级门,发送后无玩家维度的 vanilla hasTimeLeft 短路见
            // awaitDimensionBarrier 说明;仅影响被软降级的维度 worker。
            AtomicBoolean sessionDegrade = new AtomicBoolean(false);
            java.util.Map<ParallelTickUnit, Integer> ranByUnit = new java.util.concurrent.ConcurrentHashMap<>();
            // 主线程只做总控：所有维度（含玩家维度）的 tick 全在 worker 上执行；
            // 玩家实体 tick 由 dispatchAndTick 路由主线程 POST drain（主线程只做回收）。
            for (int i = 0; i < parallelDims.size(); i++) {
                final ParallelTickUnit unit = parallelDims.get(i);
                try {
                    POOL.execute(() -> {
                        try {
                            // B 组:软降级时让 vanilla 的 hasTimeLeft 在维度 worker 上短路,
                            // 让 ServerLevel.tick 的分块刻尽早让出,加快 wind-down。
                            BooleanSupplier unitTimeLeft =
                                    () -> !sessionDegrade.get() && hasTimeLeft.getAsBoolean();
                            // 多 tick:无玩家维度跑 N 个 tick（backlog 批量消化,时钟快 N 倍）;
                            // 有玩家维度恒 1 tick（单 tick 原子性,玩家节奏不漂移）。
                            // 预算自适应:主线程落后时按缩放系数压缩 N 与会话上限。
                            double scale = currentSliceScale();
                            int maxTicks = unit.level().players().isEmpty()
                                    ? Math.max(1, (int) (PRTSFeaturesConfig.dimensionWorkerMultitick * scale)) : 1;
                            long deadline = Util.getNanos()
                                    + (long) (PRTSFeaturesConfig.dimensionWorkerSessionMs * scale) * 1_000_000L;
                            long sessionStart = Util.getNanos();
                            int ran = 0;
                            do {
                                unit.tick(unitTimeLeft);
                                ran++;
                            } while (ran < maxTicks && !sessionDegrade.get()
                                    && Util.getNanos() < deadline);
                            ranByUnit.put(unit, ran);
                            STATS.record("worker.session", Util.getNanos() - sessionStart);
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
            // degraded 维度主线程串行 tick（恢复计数进度，POST drains 正常覆盖）。
            for (ParallelTickUnit unit : degraded) {
                try {
                    tickDimensionRecovery(unit.level().dimension());
                    unit.tick(hasTimeLeft);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }
            long barrierStartNanos = Util.getNanos();
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
            STATS.record("barrier.wait", Util.getNanos() - barrierStartNanos);

            // 4. Post phase (main thread) + perWorldTickTimes in vanilla format.
            long postStartNanos = Util.getNanos();
            for (int i = 0; i < n; i++) {
                ServerLevel level = units[i].level();
                // worker 触发的实体新增（掉落/蛋/投射物/XP）必须在主线程 addEntity，
                // 排在所有 drain 之前，让同 tick 后续逻辑能看见新实体。
                RegionTickManager.drainMainThreadEntityAdds(level);
                // 维度 worker 收集的方块 tick 在主线程执行（getBlockEntity 非主线程恒 null，比较器等 BE 依赖逻辑失效）
                RegionTickManager.drainMainThreadBlockTicks(level);
                // 维度 worker 收集的 block events（runBlockEvents：活塞移动链）在主线程执行，
                // 排在方块 tick 之后保持 vanilla 顺序（计划刻 → block events → 实体/BE）。
                RegionTickManager.drainMainThreadBlockEvents(level);
                // 维度 worker 收集的方块实体 tick 在主线程执行（BE tick 依赖主线程 Bukkit API）
                RegionTickManager.drainMainThreadBlockEntityTicks(level);
                // 维度 worker 收集的装置实体 tick 在主线程执行（getBlockEntity 非主线程固定为 null）
                RegionTickManager.drainMainThreadEntityTicks(level);
                // 维度 worker 收集的同步链（ChunkMap 广播/实体管理器）在主线程执行，
                // 排在实体/BE drain 之后让广播读到本 tick 最新状态（客户端增量同步恢复）。
                drainPostSync(level);
                POST.fire(level, hasTimeLeft);
                long elapsed = Util.getNanos() - startNanos[i];
                // 多 tick 会话：perWorldTickTimes 记单 tick 均值，保 vanilla 语义。
                Integer ran = ranByUnit.get(units[i]);
                long perTick = ran != null && ran > 1 ? elapsed / ran : elapsed;
                perWorldTickTimes.computeIfAbsent(level.dimension(), k -> new long[100])[tickCount % 100] = perTick;
                recordDimensionTickTime(level.dimension(), tickCount, perTick);
                STATS.record(timerName(level.dimension()), perTick);
            }
            STATS.record("post.drain", Util.getNanos() - postStartNanos);

            // 5. Execute deferred cross-dimension transfers on the main thread.
            drainTransfers();

            // 6. Fire deferred entity load/unload events on the main thread.
            drainEvents();
        } finally {
            IN_DIMENSION_TICK.set(false);
        }
        updateSliceScale((Util.getNanos() - tickStartNanos) / 1_000_000.0);

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

    /** 维度硬超时等待 + degrade 处理;仍卡死返回 false(绝不带着在跑的 worker 进入 POST)。 */
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
        onHardTimeout(where);
        barrierTimeoutDump(where);
        LOGGER.error("[PRTS-Barrier] hard timeout ({}) with degrade action: marking {} dimension(s) degraded, "
                + "waiting one more window before giving up", where, playerlessDims.size());
        for (ResourceKey<Level> dim : playerlessDims) {
            markDegradedDimension(dim);
        }
        return latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS);
    }
    /** Barrier timeout diagnostic: dump all threads and return the crash message. */
    public static String barrierTimeoutDump(String where) {
        StringBuilder sb = new StringBuilder("PRTS barrier timeout in ").append(where)
                .append(" after ").append(PRTSFeaturesConfig.barrierTimeoutMs).append("ms");
        for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            sb.append("\n\"").append(ti.getThreadName()).append("\" ").append(ti.getThreadState());
            for (StackTraceElement element : ti.getStackTrace()) {
                sb.append("\n    at ").append(element);
            }
        }
        LOGGER.error("[PRTS-Barrier] {}", sb);
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
