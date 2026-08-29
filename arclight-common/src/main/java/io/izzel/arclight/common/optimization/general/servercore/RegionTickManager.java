/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import io.izzel.arclight.common.optimization.general.servercore.ownership.AccessViolation;
import io.izzel.arclight.common.optimization.general.servercore.ownership.ClassAffinityLedger;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.Locale;

/**
 * Region-level tick parallelism: runs the overworld's block-tick / entity-tick /
 * block-entity-tick phases on per-region worker threads, preserving vanilla order
 * within each region. Cross-region block writes are queued and applied next tick.
 */
public final class RegionTickManager {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");
    public static final String REGION_THREAD_PREFIX = "PRTS-RegionTick-";
    private static volatile int REGION_COUNT = RegionLevel.INITIAL_REGION_COUNT;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    /** 首次 tick 需在主线程执行的方块实体（如 Create 机械网络初始化），按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<BlockEntity>> MAIN_THREAD_BLOCK_ENTITIES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 主线程反射 tick 的 MethodHandle 缓存：BlockEntity 基类无 tick()，按具体类缓存。 */
    private static final ConcurrentHashMap<Class<?>, MethodHandle> MAIN_THREAD_BE_TICK_HANDLES =
            new ConcurrentHashMap<>();

    /** 当前收集计划 tick 的维度（LevelTicks.tick 收集期设置）。 */
    public static ServerLevel collectingLevel() {
        return COLLECTING_LEVEL.get();
    }

    /** 维度并行期间收集、由主线程统一执行的方块实体 tick，按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<TickingBlockEntity>> MAIN_THREAD_TE_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 维度 worker 上延迟到主线程执行的 onRemove（流体 setBlock 触发链）。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> MAIN_THREAD_REMOVALS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 维度 worker 上收集、延迟到主线程 POST 执行的方块 tick（BE 依赖逻辑在 worker 上读不到方块实体），按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<BlockTick>> MAIN_THREAD_BLOCK_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 维度并行期间收集、由主线程统一执行的实体 tick（Create 装置实体），按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<Entity>> MAIN_THREAD_ENTITY_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 并行 worker（region/dimension）上触发的实体新增（mob 掉落、鸡下蛋、投射物、
     * XP orb、scguns/结构刷怪等 addFreshEntity → addEntity 全链）按维度排队，
     * 由主线程在 barrier 后真正 addEntity。直接在工作线程执行会被 Cupboard 等
     * 第三方 mixin 当作 offthread add 再补一次，造成同 UUID 实体重复入册
     * （生产 08-22 实测 237 次 "UUID of added entity already exists"）。
     */
    private static final Map<ServerLevel, LinkedBlockingQueue<Entity>> MAIN_THREAD_ENTITY_ADDS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 主线程实体判定缓存（类名前缀走查超类链）。 */
    private static final ConcurrentHashMap<Class<?>, Boolean> MAIN_THREAD_ENTITY_CACHE = new ConcurrentHashMap<>();

    /**
     * B4: worker 上实体撞传送带时，把"注册/刷新 belt passenger"延迟到主线程执行
     * （worker 上 getBlockEntity 恒 null，无法直接操作 belt BE 的非并发 passengers map）。
     * 队列存中立类型；实际 Create BE 操作由 {@link BeltPassengerApplier} 在主线程完成，
     * 使本核心类不直接依赖 Create（无 Create 时 applier 为 null，drain 为空转）。
     */
    private static final Map<ServerLevel, LinkedBlockingQueue<BeltPassengerReg>> BELT_PASSENGER_REGS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 单维度队列上限，防止异常堆积；超过丢弃最旧（运输条目每 tick 重发，丢弃无害）。 */
    private static final int BELT_PASSENGER_MAX = 4096;

    /** worker 捕获的一次 belt passenger 注册请求（纯中立值）。 */
    public record BeltPassengerReg(Entity entity, net.minecraft.core.BlockPos pos,
                                   net.minecraft.world.level.block.state.BlockState state) {
    }

    /** 主线程执行 Create belt passenger 注册的插件式回调（由 Create-compat mixin 注册）。 */
    public interface BeltPassengerApplier {
        void apply(ServerLevel level, Entity entity, net.minecraft.core.BlockPos pos,
                   net.minecraft.world.level.block.state.BlockState state);
    }

    private static volatile BeltPassengerApplier beltPassengerApplier;

    /** Create-compat 侧在类初始化时注册主线程 belt passenger 应用逻辑。 */
    public static void setBeltPassengerApplier(BeltPassengerApplier applier) {
        beltPassengerApplier = applier;
    }

    /** worker 调用：把 belt passenger 注册请求排到本维度主线程队列（applier 未注册则忽略）。 */
    public static void queueBeltPassenger(ServerLevel level, Entity entity,
                                          net.minecraft.core.BlockPos pos,
                                          net.minecraft.world.level.block.state.BlockState state) {
        if (beltPassengerApplier == null) {
            return;
        }
        LinkedBlockingQueue<BeltPassengerReg> queue;
        synchronized (BELT_PASSENGER_REGS) {
            queue = BELT_PASSENGER_REGS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
        }
        while (queue.size() >= BELT_PASSENGER_MAX) {
            queue.poll();
        }
        queue.add(new BeltPassengerReg(entity, pos.immutable(), state));
    }

    /** 主线程 PRE 阶段调用：在 belt BE tick 之前应用本维度排队的 passenger 注册。 */
    public static void drainBeltPassengers(ServerLevel level) {
        BeltPassengerApplier applier = beltPassengerApplier;
        LinkedBlockingQueue<BeltPassengerReg> queue;
        synchronized (BELT_PASSENGER_REGS) {
            queue = BELT_PASSENGER_REGS.remove(level);
        }
        if (queue == null || applier == null) {
            return;
        }
        java.util.List<BeltPassengerReg> batch = new ArrayList<>();
        queue.drainTo(batch);
        for (BeltPassengerReg reg : batch) {
            Entity entity = reg.entity();
            if (entity == null || entity.isRemoved() || entity.level() != level) {
                continue;
            }
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(reg.pos());
            if (!((ServerChunkCacheRegionBridge) level.getChunkSource()).arclight$hasLiveChunk(cp.x, cp.z)) {
                continue;
            }
            try {
                applier.apply(level, entity, reg.pos(), reg.state());
            } catch (Throwable t) {
                LOGGER.warn("[region-tick] belt passenger apply failed at {}: {}", reg.pos(), t.toString());
            }
        }
    }

    // 需求峰值 = 无玩家维度数 × REGION_COUNT。实测 4 维度 × N=8 = 32 个 region 任务，
    // 旧上限 16 + SynchronousQueue + AbortPolicy 会 RejectedExecutionException 崩服
    // （crash-2026-08-16_11.16.50-server.txt）。上限按 4 维度 × N=16 预留 64；
    // CallerRunsPolicy 让任何瞬时超额降级为「调用线程内联执行」，绝不拒绝任务。
    private static final ThreadPoolExecutor REGION_POOL = new ThreadPoolExecutor(
            0, 64, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, REGION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** LevelTicks.schedule tasks deferred from region workers, applied on the main thread. */
    private static final ConcurrentLinkedQueue<ScheduleTask> SCHEDULE_TASKS = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    private static final AtomicBoolean IN_REGION_TICK = new AtomicBoolean(false);
    private static final ThreadLocal<RegionContext> REGION_CONTEXT = new ThreadLocal<>();

    /** 当前 region worker 正在 tick 的实体类名，供 WorldAccessGuard 归因违规。 */
    private static final ThreadLocal<String> CURRENT_ENTITY_CLASS = new ThreadLocal<>();

    /**
     * The level whose vanilla block-tick collection is currently running on this
     * thread (set around ServerChunkCache.tick during the block-tick phase). Lets
     * {@link #collectBlockTick} route into the right dimension's queues.
     */
    private static final ThreadLocal<ServerLevel> COLLECTING_LEVEL = new ThreadLocal<>();

    /** Sets the level that {@link #collectBlockTick} routes into (main/dimension thread). */
    public static void setCollectingLevel(ServerLevel level) {
        if (level == null) {
            COLLECTING_LEVEL.remove();
        } else {
            COLLECTING_LEVEL.set(level);
        }
    }

    // ===== B 组:barrier 软降级(时间切片 join) =====
    // 见 docs/2026-08-27-region-parallel-barrier-idle-fix.md §2。主线程每 tick 的 barrier
    // 等待预算从 barrierTimeoutMs(120s 硬超时)改为:本 tick 剩余预算(target - elapsed,
    // 下限 10ms),只在本 tick 已超预算(落后)时激活;正常 tick 维持 barrier 完整性。
    /** 软降级时 barrier 预算下限(文档 §2 "average 下限 10ms";也给有玩家维度一个活性下限)。 */
    static final int MIN_BARRIER_BUDGET_MS = 10;
    /** 停止信号发出后,允许迟到 worker 在实体/BE/方块刻边界退出的最大等待。 */
    static final int BARRIER_WIND_DOWN_MS = 50;
    /** 主线程该 tick 惰性盖章的起点(首个 barrier 调用时写),不入 private 字段也不依赖 MinecraftServer.haveTime。 */
    private static final Object TICK_STAMP_LOCK = new Object();
    private static final AtomicLong STAMPED_SERVER_TICK = new AtomicLong(Long.MIN_VALUE);
    private static final AtomicLong TICK_START_NANOS = new AtomicLong(0L);
    /** §1.1:上一个 region barrier 的等待耗时(ms),dispatchAndTick join 后工作用它估重叠上界。 */
    static volatile long LAST_REGION_BARRIER_WAIT_MS;

    // region 硬超时降级跟踪:degraded level 的 region 阶段由主线程串行执行(无 worker)。
    private static final Map<ResourceKey<Level>, AtomicInteger> DEGRADED_LEVELS = new ConcurrentHashMap<>();
    private static final AtomicInteger REGION_HARD_TIMEOUTS = new AtomicInteger();

    private static boolean isRegionDegraded(ServerLevel level) {
        return DEGRADED_LEVELS.containsKey(level.dimension());
    }

    private static void markRegionDegraded(ServerLevel level) {
        DEGRADED_LEVELS.computeIfAbsent(level.dimension(), k -> new AtomicInteger()).set(0);
    }

    /** 串行阶段每 tick 调用:累计正常 tick,达阈值解除降级。 */
    private static void tickRegionRecovery(ServerLevel level) {
        AtomicInteger counter = DEGRADED_LEVELS.get(level.dimension());
        if (counter != null && counter.incrementAndGet() >= PRTSFeaturesConfig.barrierTimeoutRecoverTicks) {
            DEGRADED_LEVELS.remove(level.dimension(), counter);
            LOGGER.info("[PRTS-Barrier] level {} recovered from degraded mode", level.dimension().location());
        }
    }

    /** /servercore status Barrier 行(region 侧)。 */
    static String regionBarrierStatusText() {
        StringBuilder degraded = new StringBuilder("[");
        for (ResourceKey<Level> dim : DEGRADED_LEVELS.keySet()) {
            if (degraded.length() > 1) {
                degraded.append(',');
            }
            degraded.append(dim.location());
        }
        degraded.append(']');
        return "hardTimeouts=" + REGION_HARD_TIMEOUTS.get() + " degraded=" + degraded;
    }

    /** B:惰性记录主线程当前服务器 tick 的起点。runWorkers 跨阶段多次调用,同一 tick 只盖章一次。 */
    private static long stampedTickStartNanos(long serverTick) {
        if (STAMPED_SERVER_TICK.get() != serverTick) {
            synchronized (TICK_STAMP_LOCK) {
                if (STAMPED_SERVER_TICK.get() != serverTick) {
                    STAMPED_SERVER_TICK.set(serverTick);
                    TICK_START_NANOS.set(Util.getNanos());
                }
            }
        }
        return TICK_START_NANOS.get();
    }

    /**
     * B:本区域/维度 barrier 的软降级等待预算(ms)。
     *
     * <p>正常返回 -1 = 维持完整 barrier(像 barrierTimeoutMs 一样等最慢 region 完成);
     * 返回 ≥0 = 主线程本 tick 已超预算(落后),以该上限等待,超时则软降级。
     * 文档 §2.4 激活条件"主线程已落后(ticksBehind&gt;0 或本 phase 已超 budget)"在此以
     * "elapsed &gt; target"实现为自足代理:不依赖 MinecraftServer.haveTime(private 字段),
     * 也不依赖维度并行开关是否打开。</p>
     */
    static long barrierBudgetMs(MinecraftServer server) {
        if (server == null) {
            return -1L;
        }
        long targetNs;
        long targetMs = PRTSFeaturesConfig.barrierTargetMs;
        if (targetMs <= 0L) {
            return -1L;  // 目标预算 ≤0 = 永不软降级
        }
        targetNs = targetMs * 1_000_000L;
        long tickStart = stampedTickStartNanos(server.getTickCount());
        long elapsedNs = Util.getNanos() - tickStart;
        if (elapsedNs <= targetNs) {
            return -1L;  // 未超预算,保持 barrier 完整性
        }
        long remaining = targetMs - elapsedNs / 1_000_000L;
        return Math.max(MIN_BARRIER_BUDGET_MS, Math.min(remaining, PRTSFeaturesConfig.barrierTimeoutMs));
    }

    /** B:本会话软降级门是否已置位(置位后 worker 把队列剩余项取出即丢弃,计 droppedWork)。 */
    private static boolean degradeStopRequested() {
        RegionContext ctx = REGION_CONTEXT.get();
        return ctx != null && ctx.degradeStop().get();
    }

    /** Last-seen region per entity (authority-transfer counter). */
    private static final Map<Entity, Integer> LAST_REGION = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** alternate_current 的连线图非线程安全，检测结果缓存后决定方块 tick 是否串行。 */
    private static volatile Boolean SERIALIZE_BLOCK_TICKS;

    private static boolean serializeBlockTicksForMods() {
        Boolean b = SERIALIZE_BLOCK_TICKS;
        if (b == null) {
            try {
                io.izzel.arclight.common.mod.ArclightCommon.Api api = io.izzel.arclight.common.mod.ArclightCommon.api();
                b = api != null && api.isModLoaded("alternate_current");
            } catch (Throwable t) {
                b = Boolean.TRUE;
            }
            SERIALIZE_BLOCK_TICKS = b;
        }
        return b;
    }

    /**
     * Per-dimension region state: queues, cross-region updates, and the per-tick
     * gate are isolated per ServerLevel so nether/end tick workers never mix their
     * entities into another dimension's queues. {@link #REGION_COUNT} stays global
     * because {@link RegionLevel#regionId} is a static stripe partition.
     */
    private static final ConcurrentHashMap<ServerLevel, DimensionState> DIMENSION_STATES = new ConcurrentHashMap<>();

    private static final class DimensionState {
        final ConcurrentLinkedQueue<Entity>[] entityQueues;
        final WorldWriteJournal journal;
        final ConcurrentLinkedQueue<BlockTick>[] blockTickQueues;
        final ConcurrentLinkedQueue<TickingBlockEntity>[] teTickQueues;
        volatile long appliedTick = -1L;
        volatile int[] lastEntityDist;
        volatile long[] lastRegionTimingNanos;  // per-region tick time (last entity session)
        // per-region 累计耗时/次数（仅本维度累加，评估窗口用；避免全局计时器
        // 混入其它维度的空闲样本稀释平均）。
        final long[] regionTotalNanos;
        final long[] regionCount;
        // Per-tick submit budget breaker: submissions beyond journal-max-per-tick are
        // dropped (counted, not queued); callers naturally retry next tick.
        volatile long budgetTick = -1L;
        final java.util.concurrent.atomic.LongAdder budgetUsed = new java.util.concurrent.atomic.LongAdder();
        // Auto-scale per-dimension counters (only overworld drives the decision).
        int lowPeriods = 0;
        long lastEvalTick = -1L;
        long lastEvalWallMs = 0L;  // 墙上时间间隔（低 TPS 时 tick 间隔会拉长 5 倍）
        long lastCrossRead = 0L;
        long lastTicks = 0L;
        // 上次评估的 region 计时器累计快照（窗口平均用，随 reconfigure 重建）。
        long[] lastRegionTotals = new long[0];
        long[] lastRegionCounts = new long[0];

        DimensionState(int n) {
            this.entityQueues = newQueues(n);
            this.journal = new WorldWriteJournal(n, PRTSFeaturesConfig.journalMaxPerRegion, PRTSFeaturesConfig.journalLwwDedup);
            this.blockTickQueues = newQueues(n);
            this.teTickQueues = newQueues(n);
            this.lastEntityDist = new int[n];
            this.lastRegionTimingNanos = new long[n];
            this.regionTotalNanos = new long[n];
            this.regionCount = new long[n];
        }

        @SuppressWarnings("unchecked")
        private static <T> ConcurrentLinkedQueue<T>[] newQueues(int n) {
            ConcurrentLinkedQueue<T>[] q = new ConcurrentLinkedQueue[n];
            for (int i = 0; i < n; i++) {
                q[i] = new ConcurrentLinkedQueue<>();
            }
            return q;
        }
    }

    /** Returns (and lazily creates) the region state for a level. */
    private static DimensionState state(ServerLevel level) {
        return DIMENSION_STATES.computeIfAbsent(level, k -> new DimensionState(REGION_COUNT));
    }

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[region-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("cross", "block", "redstone", "redstoneBoundary", "transfer", "read")
            .group("update", "blockTicks", "teTicks", "applied", "teMainTicks", "blockTicksMain")
            .group("journal", "lwwMerged", "budgetDropped")
            .timer("entities.mainThreadMs")
            .counter("colony.ticks")
            .timer("colony.ms")
            .counter("entities.addDeferred")
            .counter("entities.addMainDrained")
            .counter("entities.addExpired")
            .counter("schedule.drainRollover")
            .timer("schedule.drainMs")
            .gauge("schedule.backlog")
            // B 组(2026-08-27 docs/2026-08-27-region-parallel-barrier-idle-fix.md §2):
            // 主线程超预算时 barrier 时间切片 join:softDegrades = 触发软降级的次数,
            // lateRegions = 单次软降级时仍未完成的 region 数(droppedRegion 遥测);
            // droppedWork = 门置位后各区丢弃的未完成 work 项(存活实体下 tick 由 dispatch 重新入队)。
            .counter("barrier.softDegrades")
            .counter("barrier.lateRegions")
            .counter("barrier.droppedWork")
            // §1.1 低置信遥测:barrier 等待与 join 后主线程工作(players/localTicks)的
            // 可重叠量上界(max(0, barrierWait - postJoin)),仅 main-wasted-ms-telemetry 开启时记录。
            .timer("main.wastedMs")
            .build();

    private RegionTickManager() {
    }

    /** Region count for the current configuration (2/4/8). */
    public static int regionCount() {
        return REGION_COUNT;
    }

    /** Applies the configured region count (startup only, main thread). */
    public static synchronized void ensureConfigured() {
        if (CONFIGURED.get()) return;
        reconfigure(PRTSFeaturesConfig.parallelRegionCount);
        AsyncPathfindingManager.reconfigureRegions(REGION_COUNT);
        CONFIGURED.set(true);
    }

    /**
     * Rebuilds queues for a new region count and RESETS the mapping table to equal split.
     * Rebalance must NEVER call this (it would overwrite an uneven table); use
     * {@link #rebuildDimensionStates()} instead.
     */
    static synchronized void reconfigure(int n) {
        REGION_COUNT = n;
        // N=16 时把条纹宽从 8 扩到 16，保证每区仍为整数条 chunk 列（N<=8 保持 8，行为不变）。
        RegionLevel.setStripeWidth(n);
        RegionLevel.resetToEqualMapping();
        for (int i = 0; i < n; i++) {
            STATS.ensureTimer("region" + i);
            STATS.ensureGroupMember("entities", "region" + i);
        }
        STATS.ensureGroupMember("entities", "mainThread");
        // Rebuild queues for every already-seen dimension (flushes pending journal first).
        rebuildDimensionStates();
        // Rebalance warm-up: fresh timers are zero, so an immediate evaluation would
        // treat noise as imbalance; wait one full interval after every reconfigure.
        reconfigureMillis = System.currentTimeMillis();
        LOGGER.info("[region-tick] region parallelism reconfigured: count={} (perRegion={} chunks/region)",
                n, RegionLevel.STRIPE_WIDTH / n);
    }

    /**
     * Rebuilds per-dimension queues WITHOUT touching the region count or mapping table
     * (rebalance path). Flushes every dimension's pending journal on the main thread first,
     * reusing the normal apply path (applyAll) so droppedUnloaded/failed semantics stay identical.
     */
    static synchronized void rebuildDimensionStates() {
        // 守卫：必须在主线程安全窗执行；worker session 内 setBlock 会被拦截入队，
        // 若在 worker 上跑 flush 会递归自我喂队列（等价于 assert !isRegionWorker()）。
        if (isRegionWorker()) {
            LOGGER.error("[region-tick] rebuildDimensionStates called on a region worker; refusing to avoid recursive journal enqueue");
            return;
        }
        for (ServerLevel level : DIMENSION_STATES.keySet()) {
            state(level).journal.applyAll(level);
        }
        DIMENSION_STATES.replaceAll((level, st) -> new DimensionState(REGION_COUNT));
    }

    /**
     * Periodically evaluates region load and adjusts the region count between the
     * configured min and max. Called after tickChildren when all workers have latched.
     * Auto-scale only reacts to overworld load; nether/end share the resulting global
     * region count (their per-dimension queues are rebuilt by {@link #reconfigure}).
     */
    public static void evaluateAutoScale(MinecraftServer server) {
        if (!regionEnabled() || !PRTSFeaturesConfig.regionAutoScale) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        DimensionState st = state(overworld);
        int serverTick = server.getTickCount();
        long intervalTicks = Math.max(20L, PRTSFeaturesConfig.regionScaleIntervalSeconds * 20L);
        long intervalMs = Math.max(1000L, PRTSFeaturesConfig.regionScaleIntervalSeconds * 1000L);
        if (serverTick - st.lastEvalTick < intervalTicks
                && System.currentTimeMillis() - st.lastEvalWallMs < intervalMs) {
            return;
        }
        st.lastEvalTick = serverTick;
        st.lastEvalWallMs = System.currentTimeMillis();
        int n = REGION_COUNT;
        double maxAvg = 0.0;
        int active = 0;
        int[] dist = st.lastEntityDist;
        // 窗口平均：用本维度（overworld）的 per-region 累计增量；全局计时器
        // 混入其它维度的空闲样本会稀释平均（首次/重配后快照为空，增量即累计）。
        long[] totalsNow = new long[n];
        long[] countsNow = new long[n];
        for (int i = 0; i < n; i++) {
            totalsNow[i] = st.regionTotalNanos[i];
            countsNow[i] = st.regionCount[i];
            long prevTotal = i < st.lastRegionTotals.length ? st.lastRegionTotals[i] : 0L;
            long prevCount = i < st.lastRegionCounts.length ? st.lastRegionCounts[i] : 0L;
            long dt = totalsNow[i] - prevTotal;
            long dc = countsNow[i] - prevCount;
            double avg = dc > 0 ? dt / 1_000_000.0 / dc : 0.0;
            if (avg > maxAvg) maxAvg = avg;
            if (i < dist.length && dist[i] > 0) active++;
        }
        st.lastRegionTotals = totalsNow;
        st.lastRegionCounts = countsNow;
        LOGGER.info("[region-tick] auto-scale eval maxAvg={}ms active={}/{} n={}", String.format(Locale.ROOT, "%.1f", maxAvg), active, n, n);
        long crossNow = STATS.counterSum("cross.read");
        long ticksNow = STATS.counterSum("ticks");
        long crossDelta = crossNow - st.lastCrossRead;
        long ticksDelta = Math.max(1L, ticksNow - st.lastTicks);
        st.lastCrossRead = crossNow;
        st.lastTicks = ticksNow;
        double crossRatio = (double) crossDelta / ticksDelta;
        double high = PRTSFeaturesConfig.regionScaleHighMspt;
        double low = PRTSFeaturesConfig.regionScaleLowMspt;
        int min = PRTSFeaturesConfig.regionScaleMin;
        int max = PRTSFeaturesConfig.regionScaleMax;
        double crossBudget = PRTSFeaturesConfig.regionScaleCrossReadRatio;
        String reason = null;
        int target = n;
        // 扩员不检查跨区读：cross.read 计数含实体寻路的正常跨区读（密集实体
        // 场景每 tick 数千次），任何阈值都会挡住扩员；扩员拆开最慢区的收益
        // 远大于条纹边界带来的跨区读增量。降员仍检查（低负载时跨区读少）。
        if (maxAvg > high && n < max && active >= n) {
            target = n * 2;
            reason = String.format("high-load maxAvg=%.1fms active=%d crossRatio=%.3f", maxAvg, active, crossRatio);
            st.lowPeriods = 0;
        } else if (maxAvg < low && active <= n / 2 && crossRatio <= crossBudget) {
            if (++st.lowPeriods >= PRTSFeaturesConfig.regionScaleStablePeriods && n > min) {
                target = n / 2;
                reason = String.format("low-load maxAvg=%.1fms active=%d periods=%d", maxAvg, active, st.lowPeriods);
                st.lowPeriods = 0;
            }
        } else {
            st.lowPeriods = 0;
        }
        if (target != n) {
            reconfigure(target);
            AsyncPathfindingManager.reconfigureRegions(target);
            LOGGER.info("[region-tick] auto-scale: {} -> {} reason={}", n, target, reason);
        }
    }

    // Rebalance cadence snapshot (overworld-driven; independent from auto-scale counters).
    private static long lastRebalanceEvalTick = -1L;
    private static long reconfigureMillis = 0L;
    private static boolean lastRebalanceDenseSkipLogged = false;

    /**
     * Uneven-stripes rebalance: moves one boundary group from the highest-load region to the
     * lowest-load adjacent region. Runs AFTER {@link #evaluateAutoScale} at the same
     * tickChildren RETURN window; the caller skips this round when auto-scale changed the count.
     */
    public static void evaluateRebalance(net.minecraft.server.MinecraftServer server) {
        if (!regionEnabled() || !PRTSFeaturesConfig.unevenStripes) {
            return;
        }
        int regionCount = regionCount();
        if (regionCount < 2) {
            return;
        }
        // Gate: per-region width already at the lower bound -> no group can be moved.
        // Movable only for N in {2,4} (N=8/16 give width=1 group/region).
        int stripeWidth = RegionLevel.stripeWidth();
        if (stripeWidth / regionCount <= PRTSFeaturesConfig.rebalanceMinGroups) {
            if (!lastRebalanceDenseSkipLogged) {
                LOGGER.info("[region-tick] rebalance: skipped, regions too dense (width={} groups/region)",
                        stripeWidth / regionCount);
                lastRebalanceDenseSkipLogged = true;
            }
            return;
        }
        lastRebalanceDenseSkipLogged = false;
        long now = server.getTickCount();
        long intervalTicks = PRTSFeaturesConfig.rebalanceIntervalSeconds * 20L;
        if (lastRebalanceEvalTick >= 0 && now - lastRebalanceEvalTick < intervalTicks) {
            return;
        }
        // Warm-up gate: skip the first interval right after reconfigure (boot/auto-scale),
        // when timing samples are sparse and idle noise looks like an imbalance.
        if (System.currentTimeMillis() - reconfigureMillis < PRTSFeaturesConfig.rebalanceIntervalSeconds * 1000L) {
            return;
        }
        lastRebalanceEvalTick = now;
        // width[] computed on demand from the mapping table (no cached state).
        int[] widths = RegionLevel.regionWidths();
        double[] avgMs = new double[regionCount];
        double[] norms = new double[regionCount];
        for (int i = 0; i < regionCount; i++) {
            avgMs[i] = STATS.avgMillis("region" + i);
            norms[i] = widths[i] > 0 ? avgMs[i] / widths[i] : 0.0;
        }
        int H = -1;
        int L = -1;
        for (int i = 0; i < regionCount; i++) {
            if (widths[i] <= 0) {
                continue;
            }
            if (H < 0 || norms[i] > norms[H]) {
                H = i;
            }
            if (L < 0 || norms[i] < norms[L]) {
                L = i;
            }
        }
        if (H < 0 || L < 0 || H == L) {
            return;
        }
        // Data sufficiency: a zero minimum means an unsampled region, which would
        // pass the ratio gate with any noise; wait for real measurements instead.
        if (norms[L] <= 0.0) {
            return;
        }
        if (norms[H] <= norms[L] * PRTSFeaturesConfig.rebalanceImbalanceRatio) {
            return;
        }
        int[] table = RegionLevel.mappingSnapshot();
        if (table == null) {
            return;
        }
        int bestGroup = pickRebalanceGroup(table, widths, avgMs, norms, H, L, stripeWidth);
        if (bestGroup < 0) {
            return;
        }
        table[bestGroup] = L;
        RegionLevel.applyMapping(table);
        rebuildDimensionStates();
        LOGGER.info("[region-tick] rebalance: move group={} r{} -> r{} normMax={} normMin={} widths={} note: cross.transfer spike expected next window",
                bestGroup, H, L, String.format("%.1f", norms[H]), String.format("%.1f", norms[L]),
                java.util.Arrays.toString(widths));
    }

    /**
     * Picks the H-region endpoint group whose move to L shrinks the normalized gap most.
     * Returns -1 when no candidate passes the reject conditions or shrinks the gap.
     */
    private static int pickRebalanceGroup(int[] table, int[] widths, double[] avgMs, double[] norms,
                                          int H, int L, int stripeWidth) {
        int minGroups = PRTSFeaturesConfig.rebalanceMinGroups;
        // Reject 1: H must keep at least minGroups after giving one group away.
        if (widths[H] - 1 < minGroups) {
            return -1;
        }
        double bestGap = Math.abs(norms[H] - norms[L]);
        double perGroupH = avgMs[H] / widths[H];
        int bestGroup = -1;
        for (int g = 0; g < stripeWidth; g++) {
            if (table[g] != H) {
                continue;
            }
            int left = table[Math.floorMod(g - 1, stripeWidth)];
            int right = table[Math.floorMod(g + 1, stripeWidth)];
            boolean leftOut = left != H;
            boolean rightOut = right != H;
            // Endpoint: exactly one neighbor lies outside H (segment interior or isolated group skipped).
            if (leftOut == rightOut) {
                continue;
            }
            int outerRegion = leftOut ? left : right;
            // Reject 2: outer neighbor must be L (keeps contiguous segments, adds no new boundary).
            if (outerRegion != L) {
                continue;
            }
            // Prediction (3.2.5): raw-ms add/subtract, then compare the normalized gap.
            double newMsH = avgMs[H] - perGroupH;
            double newMsL = avgMs[L] + perGroupH;
            int newWidthH = widths[H] - 1;
            int newWidthL = widths[L] + 1;
            if (newWidthH <= 0) {
                continue;
            }
            double gapAfter = Math.abs(newMsH / newWidthH - newMsL / newWidthL);
            // Only move if the prediction strictly shrinks the current normalized gap.
            if (gapAfter >= bestGap) {
                continue;
            }
            bestGap = gapAfter;
            bestGroup = g;
        }
        return bestGroup;
    }

    /** True on a region tick worker thread. */
    public static boolean isRegionTickThread() {
        return Thread.currentThread().getName().startsWith(REGION_THREAD_PREFIX);
    }

    /** True while region workers are ticking (any parallel tick unit active). */
    public static boolean inRegionTick() {
        return IN_REGION_TICK.get();
    }

    /** Region id owned by the current region worker (-1 outside region tick). */
    public static int currentRegion() {
        RegionContext ctx = REGION_CONTEXT.get();
        return ctx == null ? -1 : ctx.regionId();
    }

    /** True when the current thread is a region worker inside a region session. */
    public static boolean isRegionWorker() {
        return REGION_CONTEXT.get() != null;
    }

    /** True when the current thread is the region worker of the given level. */
    public static boolean isRegionWorkerFor(ServerLevel level) {
        RegionContext ctx = REGION_CONTEXT.get();
        return ctx != null && ctx.level() == level;
    }

    /** 区块环境子任务进入其所属 region 上下文(跨区写/计划刻/实体新增走既有 worker 路径)。 */
    public static void enterChunkEnvContext(ServerLevel level, int region) {
        REGION_CONTEXT.set(new RegionContext(region, level, new AtomicBoolean(false)));
    }

    /** 实体批子任务进入发起 region 的上下文(共享停止门,软降级可 propagate 到批内)。 */
    public static void enterRegionContext(ServerLevel level, int region, AtomicBoolean degradeStop) {
        REGION_CONTEXT.set(new RegionContext(region, level, degradeStop));
    }

    /** 退出区块环境/实体批子任务上下文。 */
    public static void exitRegionContext() {
        REGION_CONTEXT.remove();
    }

    /** 实体批子任务内的实体 tick 入口(等价 region worker 的 tickEntity)。 */
    public static void tickEntityInContext(ServerLevel level, Entity entity) {
        tickEntity(level, entity);
    }

    /** 当前 region 上下文的停止门(无上下文返回 null)。 */
    public static AtomicBoolean currentDegradeStop() {
        RegionContext ctx = REGION_CONTEXT.get();
        return ctx == null ? null : ctx.degradeStop();
    }

    /** Entity class currently ticking on this region worker, or null outside entity ticks. */
    public static String currentEntityClassName() {
        return CURRENT_ENTITY_CLASS.get();
    }

    /** 把一个方块实体排到主线程队列（下次主线程 PRE 阶段执行其 tick）。 */
    public static void queueMainThreadBlockEntity(BlockEntity be) {
        if (be.getLevel() instanceof ServerLevel level) {
            LinkedBlockingQueue<BlockEntity> queue;
            synchronized (MAIN_THREAD_BLOCK_ENTITIES) {
                queue = MAIN_THREAD_BLOCK_ENTITIES.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
            }
            queue.add(be);
        }
    }

    /** 主线程 PRE 阶段调用：执行本维度排队的主线程方块实体 tick。 */
    public static void drainMainThreadBlockEntities(ServerLevel level) {
        LinkedBlockingQueue<BlockEntity> queue;
        synchronized (MAIN_THREAD_BLOCK_ENTITIES) {
            queue = MAIN_THREAD_BLOCK_ENTITIES.remove(level);
        }
        if (queue == null) {
            return;
        }
        java.util.Collection<BlockEntity> batch = new ArrayList<>();
        queue.drainTo(batch);
        for (BlockEntity be : batch) {
            long start = Util.getNanos();
            try {
                // 方块实体子类各自定义 tick()（BlockEntity 基类无此方法）；getMethod 只在首次缓存，
                // 之后走 MethodHandle，避免每 tick 反射查找/调用（BE 并行时该队列每 tick 都有 Create BE）。
                MethodHandle handle = MAIN_THREAD_BE_TICK_HANDLES.computeIfAbsent(be.getClass(), cls -> {
                    try {
                        return MethodHandles.lookup().unreflect(cls.getMethod("tick"));
                    } catch (Throwable ignored) {
                        return null;
                    }
                });
                if (handle != null) {
                    handle.invoke(be);
                } else {
                    be.getClass().getMethod("tick").invoke(be);
                }
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block entity tick failed at {}: {}",
                        be.getBlockPos(), t.toString());
            } finally {
                String beType = blockEntityTypeKey(be);
                if (BlockEntityTickStats.record(beType, Util.getNanos() - start)) {
                    BlockEntityTickStats.recordMaxPos(beType, be.getBlockPos());
                }
            }
        }
    }

    /** 维度 worker 调用：把方块 tick 排到本维度的主线程队列（worker 上 getBlockEntity 恒 null，BE 依赖逻辑失效）。 */
    public static void queueMainThreadBlockTick(ServerLevel level, BlockPos pos, Block block) {
        LinkedBlockingQueue<BlockTick> queue;
        synchronized (MAIN_THREAD_BLOCK_TICKS) {
            queue = MAIN_THREAD_BLOCK_TICKS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
        }
        queue.add(new BlockTick(pos, block));
    }

    /** 主线程 POST 阶段调用：执行本维度排队的主线程方块 tick。 */
    public static void drainMainThreadBlockTicks(ServerLevel level) {
        LinkedBlockingQueue<BlockTick> queue;
        synchronized (MAIN_THREAD_BLOCK_TICKS) {
            queue = MAIN_THREAD_BLOCK_TICKS.remove(level);
        }
        if (queue == null) {
            return;
        }
        BlockTick bt;
        while ((bt = queue.poll()) != null) {
            try {
                ((ServerLevelRegionBlockTickAccess) level).arclight$tickBlock(bt.pos(), bt.block());
                STATS.increment("update.blockTicksMain");
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block tick failed at {}: {}",
                        bt.pos(), t.toString());
            }
        }
    }

    /** 维度 worker 调用：把方块实体 tick 排到本维度的主线程队列。 */
    public static void queueMainThreadBlockEntityTick(ServerLevel level, TickingBlockEntity ticker) {
        LinkedBlockingQueue<TickingBlockEntity> queue;
        synchronized (MAIN_THREAD_TE_TICKS) {
            queue = MAIN_THREAD_TE_TICKS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
        }
        queue.add(ticker);
    }

    /** 主线程 POST 阶段调用：执行本维度排队的主线程方块实体 tick。 */
    public static void drainMainThreadBlockEntityTicks(ServerLevel level) {
        LinkedBlockingQueue<TickingBlockEntity> queue;
        synchronized (MAIN_THREAD_TE_TICKS) {
            queue = MAIN_THREAD_TE_TICKS.remove(level);
        }
        if (queue == null) {
            return;
        }
        java.util.Collection<TickingBlockEntity> batch = new ArrayList<>();
        queue.drainTo(batch);
        for (TickingBlockEntity ticker : batch) {
            long start = Util.getNanos();
            try {
                ticker.tick();
                STATS.increment("update.teMainTicks");
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block entity tick failed at {}: {}",
                        ticker.getPos(), t.toString());
            } finally {
                String teType = blockEntityTypeKey(ticker);
                if (BlockEntityTickStats.record(teType, Util.getNanos() - start)) {
                    BlockEntityTickStats.recordMaxPos(teType, ticker.getPos());
                }
            }
        }
        Runnable removal;
        while ((removal = MAIN_THREAD_REMOVALS.poll()) != null) {
            try {
                removal.run();
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block removal failed: {}", t.toString());
            }
        }
        drainMainThreadBlockTicks(level);
    }

    /** 维度 worker 调用：把 onRemove 延迟到主线程执行（流体 setBlock 触发的
     *  onRemove 会经 Minecolonies 钩子跨线程读 BE，排主线程保语义）。 */
    public static void queueMainThreadBlockRemoval(Runnable removal) {
        MAIN_THREAD_REMOVALS.add(removal);
    }

    /** 维度 worker 调用：把实体 tick 排到本维度的主线程队列。 */
    public static void queueMainThreadEntityTick(ServerLevel level, Entity entity) {
        LinkedBlockingQueue<Entity> queue;
        synchronized (MAIN_THREAD_ENTITY_TICKS) {
            queue = MAIN_THREAD_ENTITY_TICKS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
        }
        queue.add(entity);
    }

    /** 主线程 POST 阶段调用：执行本维度排队的主线程实体 tick，支持分批。 */
    public static void drainMainThreadEntityTicks(ServerLevel level) {
        LinkedBlockingQueue<Entity> queue;
        synchronized (MAIN_THREAD_ENTITY_TICKS) {
            queue = MAIN_THREAD_ENTITY_TICKS.get(level);  // get (not remove), persist queue
            if (queue == null) {
                return;
            }
        }
        long start = Util.getNanos();
        int queueDepth = queue.size();  // capture before drain
        int budget = PRTSFeaturesConfig.mainThreadEntityDrainBudget;
        boolean batched = budget > 0;
        int processed = 0;
        int skippedExpired = 0;

        RoutedDrainStats.Accumulator acc = MAIN_DRAIN_ACC;
        acc.setQueueDepth(queueDepth);

        // Poll loop with budget cap (or unbounded if budget=0)
        Entity entity;
        while ((entity = queue.poll()) != null) {
            if (batched && processed >= budget) {
                // Budget exhausted; put back and stop (re-offer to front is not atomic, but queue.offer is fine)
                queue.offer(entity);
                break;
            }
            // Entity expiry validation (remove if invalid, don't tick)
            if (entity.isRemoved() || entity.level() != level) {
                skippedExpired++;
                continue;
            }

            Class<?> cls = entity.getClass();
            boolean colony = cls.getName().startsWith("com.minecolonies.");
            long entityStart = Util.getNanos();
            try {
                VillagerPathBudget.enterRoutedEntityTick();
                tickEntitySafely(level, entity);
                STATS.increment("entities.mainThread");
                if (colony) {
                    STATS.increment("colony.ticks");
                    STATS.record("colony.ms", Util.getNanos() - entityStart);
                }
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread entity tick failed at {}: {}",
                        entity.blockPosition(), t.toString());
            } finally {
                VillagerPathBudget.exitRoutedEntityTick();
                DrainClassInfo info = drainClassInfo(cls);
                acc.record(info.name, info.reason, Util.getNanos() - entityStart);
            }
            processed++;
        }
        acc.flush();
        STATS.record("entities.mainThreadMs", Util.getNanos() - start);
        if (skippedExpired > 0) {
            STATS.add("entities.mainThreadExpired", skippedExpired);
        }
    }

    /**
     * 并行 worker（region/dimension）调用：把实体新增排队到本维度主线程队列。
     * 只排队不执行 —— addEntity 必须在主线程落地，否则第三方 mixin（Cupboard 等）
     * 会按 offthread add 处理并补跑一次，造成同 UUID 实体重复入册。
     */
    public static void queueMainThreadEntityAdd(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            LinkedBlockingQueue<Entity> queue;
            synchronized (MAIN_THREAD_ENTITY_ADDS) {
                queue = MAIN_THREAD_ENTITY_ADDS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
            }
            queue.add(entity);
            STATS.increment("entities.addDeferred");
        }
    }

    /**
     * 主线程调用：真正执行 worker 排队的实体新增。
     * 调用点：区域调度在主线程完成后的 runWorkers 尾部；维度 worker 场景由
     * {@link DimensionTickManager} POST 阶段按维度调用。
     */
    public static void drainMainThreadEntityAdds(ServerLevel level) {
        LinkedBlockingQueue<Entity> queue;
        synchronized (MAIN_THREAD_ENTITY_ADDS) {
            queue = MAIN_THREAD_ENTITY_ADDS.get(level);
        }
        if (queue == null) {
            return;
        }
        Entity entity;
        while ((entity = queue.poll()) != null) {
            // 排队窗口内实体可能已被移除/换维度（与 entity tick drain 同款过期校验）
            if (entity.isRemoved() || entity.level() != level) {
                STATS.increment("entities.addExpired");
                continue;
            }
            // 关服窗口的残留直接丢弃，避免向关闭中的 level 追加实体
            if (level.getServer() == null || !level.getServer().isRunning()) {
                STATS.increment("entities.addExpired");
                continue;
            }
            // addEntity 是 private，public 入口为 addFreshEntity；主线程路径会再走
            // Bukkit 实体新增事件（DEFAULT 原因），与 vanilla addFreshEntity 语义一致。
            if (!level.addFreshEntity(entity)) {
                LOGGER.debug("[region-tick] main-thread deferred entity add rejected: {}", entity);
            }
            STATS.increment("entities.addMainDrained");
        }
    }

    /** Cached class label + routed reason for drain telemetry (avoids per-tick getName/classify). */
    private record DrainClassInfo(String name, String reason) {
    }

    private static final ConcurrentHashMap<Class<?>, DrainClassInfo> DRAIN_CLASS_INFO = new ConcurrentHashMap<>();

    /** Reused per-pass accumulator; drain runs on the main thread only. */
    private static final RoutedDrainStats.Accumulator MAIN_DRAIN_ACC = new RoutedDrainStats.Accumulator();

    private static DrainClassInfo drainClassInfo(Class<?> cls) {
        DrainClassInfo info = DRAIN_CLASS_INFO.get(cls);
        if (info == null) {
            info = new DrainClassInfo(cls.getName().intern(), routedReason(cls));
            DRAIN_CLASS_INFO.put(cls, info);
        }
        return info;
    }

    /**
     * 主线程实体判定：这些 mod 实体的 tick 会调用 Level.getBlockEntity（1.21.1 在
     * 非主线程固定返回 null）或读写主线程专属全局状态，在 region worker 上会自毁/失效。
     */
    private static final String[] MAIN_THREAD_ENTITY_PREFIXES = {
            "com.simibubi.create.content.contraptions.", // Create 装置/座椅
            "com.simibubi.create.content.trains.",       // Create 列车实体
            "com.minecolonies.",                          // 殖民地市民/袭击者/矿车
            "com.arxyt.colonypathingedition.",
            "com.dannyboythomas.hole_filler_mod.",        // 填洞球等投掷实体(落地放方块+取 BE)
            "net.minecraft.world.entity.vehicle.",        // 矿车/船：onMinecartPass→getBlockEntity 触发装配站
            "net.minecraft.world.entity.boss.enderdragon.", // 末影龙：飞行 AI 读地形，worker 空气回退会让它悬停
    };

    public static boolean needsMainThreadTick(Entity entity) {
        Class<?> c = entity.getClass();
        // 1) force：显式强制主线程（最高优先级）
        if (matchesAnyConfiguredPrefix(c, PRTSFeaturesConfig.mainThreadEntityForce)) {
            return true;
        }
        // 2) allow：显式放行（危险调试用，覆盖种子与学习结果）
        if (matchesAnyConfiguredPrefix(c, PRTSFeaturesConfig.mainThreadEntityAllow)) {
            return false;
        }
        // 3) worker 任意异常安全阀：本会话永久回主线程
        if (EntityAffinity.isUnsafe(c.getName())) {
            return true;
        }
        // 4) 种子前缀：人工沉淀的主线程实体知识，作为学习器的初始规则
        Boolean seeded = MAIN_THREAD_ENTITY_CACHE.get(c);
        if (seeded == null) {
            seeded = matchesAnyPrefix(c, MAIN_THREAD_ENTITY_PREFIXES);
            MAIN_THREAD_ENTITY_CACHE.put(c, seeded);
        }
        if (seeded) {
            return true;
        }
        // 5) 运行时学习：违规窗口超额路由（下 tick 起生效）
        if (!"auto".equals(PRTSFeaturesConfig.mainThreadRouting)) {
            return false;
        }
        long tick = entity.level().getServer() != null ? entity.level().getServer().getTickCount() : 0L;
        String className = c.getName();
        // Probation: override routed flag temporarily to test on worker
        if (ClassAffinityLedger.shouldProbation(className, tick)) {
            return false;  // Send to worker for probation tick
        }
        return ClassAffinityLedger.shouldRouteMainThread(className, tick);
    }

    /**
     * Read-only classification of why an entity class is routed to the main thread
     * (attribution telemetry). Mirrors {@link #needsMainThreadTick} precedence without
     * side effects; returns a short stable label for attribution.
     */
    public static String routedReason(Class<?> c) {
        if (matchesAnyConfiguredPrefix(c, PRTSFeaturesConfig.mainThreadEntityForce)) {
            return "force";
        }
        if (EntityAffinity.isUnsafe(c.getName())) {
            return "unsafe";
        }
        Boolean seeded = MAIN_THREAD_ENTITY_CACHE.get(c);
        if (seeded == null) {
            seeded = matchesAnyPrefix(c, MAIN_THREAD_ENTITY_PREFIXES);
        }
        if (Boolean.TRUE.equals(seeded)) {
            return "seed";
        }
        if ("auto".equals(PRTSFeaturesConfig.mainThreadRouting)) {
            return "auto-ledger";
        }
        return "other";
    }

    /** 沿类继承链匹配任意前缀（Entity 自身为止）。 */
    private static boolean matchesAnyPrefix(Class<?> c, String[] prefixes) {
        Class<?> sup = c;
        while (sup != null && sup != Entity.class) {
            String name = sup.getName();
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            sup = sup.getSuperclass();
        }
        return false;
    }

    private static boolean matchesAnyConfiguredPrefix(Class<?> c, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        return matchesAnyPrefix(c, prefixes.toArray(new String[0]));
    }

    /** 主线程实体路由：维度并行期间排主线程队列，否则当前线程（主线程）就地 tick。 */
    private static void routeMainThreadEntity(ServerLevel level, Entity entity) {
        if (DimensionTickManager.inDimensionTick()) {
            queueMainThreadEntityTick(level, entity);
        } else {
            tickEntitySafely(level, entity);
        }
    }

    /** True when the region-parallel feature is enabled (PRTS prts-features.yml). */
    public static boolean regionEnabled() {
        // 整服熔断激活时退原版串行(region 各阶段按 false 走 vanilla 路径)。
        return PRTSFeaturesConfig.parallelRegion && !DimensionTickManager.isFaultFallback();
    }

    /** True when a region worker is about to write a block outside its own region. */
    public static boolean isCrossWrite(BlockPos pos) {
        int r = currentRegion();
        return r >= 0 && !RegionLevel.isAuthoritative(pos, r);
    }

    /** Counts a cross-region block read by a region worker (read stays vanilla). */
    public static void countCrossRead(BlockPos pos) {
        int r = currentRegion();
        if (r >= 0 && !RegionLevel.isAuthoritative(pos, r)) {
            STATS.increment("cross.read");
        }
    }

    /** Collects a cross-region write into the target region's journal (Level.setBlock interception). */
    public static void collectCrossWrite(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        int target = RegionLevel.regionId(pos);
        long tick = level.getServer() != null ? level.getServer().getTickCount() : 0L;
        String source = CURRENT_ENTITY_CLASS.get();
        if (source == null || source.isEmpty()) {
            source = Thread.currentThread().getName();
        }
        DimensionState st = state(level);
        // Budget breaker: cap per-dimension submissions per tick; dropped entries are
        // retried by the caller next tick (the mixin returns false either way).
        int budget = PRTSFeaturesConfig.journalMaxPerTick;
        if (budget > 0) {
            if (st.budgetTick != tick) {
                synchronized (st) {
                    if (st.budgetTick != tick) {
                        st.budgetTick = tick;
                        st.budgetUsed.reset();
                    }
                }
            }
            if (st.budgetUsed.sum() >= budget) {
                st.journal.recordBudgetDrop();
                STATS.increment("journal.budgetDropped");
                return;
            }
            st.budgetUsed.increment();
        }
        st.journal.submit(target, new WorldWriteJournal.Entry(pos, state, flags, tick, source));
        STATS.increment("cross.block");
        if (isRedstone(state.getBlock())) {
            STATS.increment("cross.redstone");
            if (isBoundaryColumn(pos)) {
                STATS.increment("cross.redstoneBoundary");
            }
        }
    }

    /** LWW merge telemetry hook, called from WorldWriteJournal.submit under the queue lock. */
    static void noteJournalLwwMerge() {
        STATS.increment("journal.lwwMerged");
    }

    /** True if the column sits in a boundary group (neighboring group belongs to a different region). */
    private static boolean isBoundaryColumn(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int width = RegionLevel.stripeWidth();
        int group = Math.floorMod(chunkX, width);
        int regionId = RegionLevel.regionIdOfGroup(group);
        int leftRegion = RegionLevel.regionIdOfGroup(Math.floorMod(group - 1, width));
        int rightRegion = RegionLevel.regionIdOfGroup(Math.floorMod(group + 1, width));
        return regionId != leftRegion || regionId != rightRegion;
    }

    /** Collects a due scheduled block tick into the owning region's queue. */
    public static void collectBlockTick(BlockPos pos, Block block) {
        ServerLevel level = COLLECTING_LEVEL.get();
        if (level == null) {
            return;
        }
        int r = RegionLevel.regionId(pos);
        state(level).blockTickQueues[r].add(new BlockTick(pos, block));
        STATS.increment("update.blockTicks");
    }

    /** Collects a ticking block entity into the owning region's queue. */
    public static void collectBlockEntityTick(ServerLevel level, TickingBlockEntity ticker) {
        int r = RegionLevel.regionId(ticker.getPos());
        state(level).teTickQueues[r].add(ticker);
        STATS.increment("update.teTicks");
    }

    /** BE 三档判定：allow 列表 + force 列表 + 违规台账自动降级。 */
    public static boolean shouldParallelTickBlockEntity(ServerLevel level, TickingBlockEntity ticker) {
        if (!regionEnabled() || !PRTSFeaturesConfig.regionBlockEntityParallel) {
            return false;
        }
        long tick = level.getServer() != null ? level.getServer().getTickCount() : 0L;
        return BlockEntityAffinity.shouldRunOnWorker(blockEntityTypeKey(ticker), tick);
    }

    /** Defers a LevelTicks.schedule call to the main thread (LevelTicks is not thread-safe). */
    public static void collectScheduleTick(LevelTicks<?> owner, ScheduledTick<?> tick) {
        SCHEDULE_TASKS.add(new ScheduleTask(owner, tick));
    }

    /** Applies deferred scheduling tasks on the main thread after a region session.
     *
     * <p>限时 drain：风暴期（成批新生块灌入计划 tick，液体扩散回环）积压可达数十万级，
     * 无界清空会把维度 tick 线程卡死数分钟直至屏障超时崩溃（2026-08-25 纯净服风暴实测）。
     * 按时间预算分批落地，剩余顺延下一 tick；ScheduledTick 触发时刻为绝对 gameTime，
     * 晚落地几 tick 只会让少数计划 tick 略晚触发，与原版高负载下的延迟行为同向。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void drainScheduleTasks() {
        long start = Util.getNanos();
        long deadline = start + PRTSFeaturesConfig.scheduleDrainBudgetMs * 1_000_000L;
        int drained = 0;
        ScheduleTask task;
        while ((task = SCHEDULE_TASKS.poll()) != null) {
            try {
                task.owner().schedule(task.tick());
            } catch (Throwable t) {
                LOGGER.warn("[region-tick] deferred schedule failed: {}", t.toString());
            }
            drained++;
            // 每 1024 个检查一次预算，摊薄 nanoTime 开销
            if ((drained & 0x3FF) == 0 && Util.getNanos() >= deadline) {
                STATS.increment("schedule.drainRollover");
                break;
            }
        }
        STATS.record("schedule.drainMs", Util.getNanos() - start);
        STATS.setGauge("schedule.backlog", SCHEDULE_TASKS.size());
    }

    /** Session 1: runs collected scheduled block ticks on region workers (per-tick gate for cross-region updates). */
    public static boolean runBlockTickPhase(ServerLevel level) {
        if (!regionEnabled()) {
            return false;
        }
        ensureConfigured();
        DimensionState st = state(level);
        long gameTime = level.getGameTime();
        boolean applyNow = st.appliedTick != gameTime;
        if (applyNow) {
            st.appliedTick = gameTime;
        }
        if (!applyNow && isEmpty(st.blockTickQueues)) {
            return false;
        }
        if (applyNow && isEmpty(st.blockTickQueues) && st.journal.isEmpty()) {
            return false;
        }
        if (serializeBlockTicksForMods()) {
            // 红石网络类优化 mod（alternate_current）的连线图非线程安全：
            // 并行方块 tick 会撕裂网络节点引用，此处回退串行（每维度单线程）。
            // 当前线程为主线程时 inline 执行（BE 可用）；在维度 worker 上时
            // getBlockEntity 恒 null（比较器等 BE 依赖逻辑失效），延迟到主线程 POST 执行。
            boolean deferToMainThread = PRTSFeaturesConfig.blockTickMainThreadWhenSerialized
                    && Thread.currentThread() != ((LevelMainThreadAccess) level).arclight$getMainThread();
            for (int r = 0; r < st.blockTickQueues.length; r++) {
                BlockTick bt;
                while ((bt = st.blockTickQueues[r].poll()) != null) {
                    if (deferToMainThread) {
                        queueMainThreadBlockTick(level, bt.pos(), bt.block());
                    } else {
                        ((ServerLevelRegionBlockTickAccess) level).arclight$tickBlock(bt.pos(), bt.block());
                    }
                }
            }
            if (applyNow) {
                for (int r = 0; r < regionCount(); r++) {
                    st.journal.apply(level, r);
                }
            }
            return true;
        }
        runWorkers(level, applyNow, region -> {
            BlockTick bt;
            while ((bt = st.blockTickQueues[region].poll()) != null) {
                if (RegionTickManager.degradeStopRequested()) {
                    // B 组软降级:该 region 本轮未达成的方块刻丢弃(计 droppedWork),
                    // 保证队列底部不被遗留跨 tick——下一 tick 的 vanilla 收集会重新入队。
                    STATS.increment("barrier.droppedWork");
                    continue;
                }
                ((ServerLevelRegionBlockTickAccess) level).arclight$tickBlock(bt.pos(), bt.block());
            }
        });
        return true;
    }

    /** Session 3: runs collected block-entity ticks on region workers. */
    public static boolean runBlockEntityTickPhase(ServerLevel level) {
        if (!regionEnabled()) {
            return false;
        }
        ensureConfigured();
        DimensionState st = state(level);
        if (isEmpty(st.teTickQueues)) {
            return false;
        }
        long gameTime = level.getGameTime();
        boolean applyNow = st.appliedTick != gameTime;
        if (applyNow) {
            st.appliedTick = gameTime;
        }
        runWorkers(level, applyNow, region -> {
            TickingBlockEntity te;
            while ((te = st.teTickQueues[region].poll()) != null) {
                if (RegionTickManager.degradeStopRequested()) {
                    // B 组软降级:该 region 本轮未达成的 BE/方块刻丢弃(计 droppedWork)。
                    STATS.increment("barrier.droppedWork");
                    continue;
                }
                String typeKey = blockEntityTypeKey(te);
                CURRENT_ENTITY_CLASS.set("block-entity:" + typeKey);
                long start = Util.getNanos();
                try {
                    te.tick();
                } catch (Throwable t) {
                    // 任何异常都不允许从 BE 并行会话冒泡成服务器崩溃：
                    // 该类型本会话永久降级回主线程（含非世界访问类的 mod 竞态）。
                    BlockEntityAffinity.markUnsafe(typeKey);
                    LOGGER.error("[region-tick] BE worker tick failed for {} at {}: {}",
                            typeKey, te.getPos(), t.toString());
                } finally {
                    if (BlockEntityTickStats.record(typeKey, Util.getNanos() - start)) {
                        BlockEntityTickStats.recordMaxPos(typeKey, te.getPos());
                    }
                    CURRENT_ENTITY_CLASS.remove();
                }
            }
        });
        return true;
    }

    /** Session 2 (entity tick): dispatches the entity list by region and ticks on region workers. */
    public static boolean dispatchAndTick(ServerLevel level, EntityTickList list, Consumer<Entity> consumer) {
        if (!regionEnabled()) {
            return false;
        }
        ensureConfigured();
        DimensionState st = state(level);
        STATS.increment("ticks");

        // 1. Dispatch (dimension worker; no concurrent write to the tick list here).
        //    玩家与拾取交互实体（物品/经验球）不进区域 worker：拾取/推挤/容器菜单
        //    依赖玩家所在线程时序，跨线程读写会造成拾取失效与移动抖动。
        int[] perRegion = new int[REGION_COUNT];
        List<Entity> localTicks = new ArrayList<>();
        list.forEach(entity -> {
            if (entity instanceof ServerPlayer || entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
                localTicks.add(entity);
                return;
            }
            // 镜像 vanilla WorldServer.tick 实体循环的顺序：shouldDiscardEntity ->
            // checkDespawn -> 进 ticking 范围才 tick。region worker 上只做 tick，
            // discard/despawn 决策必须在维度 tick 线程完成（与 vanilla 相同）。
            if (!level.getServer().isSpawningAnimals()
                    && (entity instanceof Animal || entity instanceof WaterAnimal)) {
                entity.discard();
                return;
            }
            if (!level.getServer().areNpcsEnabled() && entity instanceof Npc) {
                entity.discard();
                return;
            }
            if (!level.tickRateManager().isEntityFrozen(entity)) {
                // vanilla checkDespawn 对 isPersistenceRequired 实体只重置 noActionTime（永不 discard），
                // 5000 村民基准下这是纯主线程串行开销：persistent 实体直接跳过整个检查（语义等价）。
                if (!(entity instanceof Mob mob) || !mob.isPersistenceRequired()) {
                    entity.checkDespawn();
                    if (entity.isRemoved()) {
                        return;
                    }
                }
            }
            if (needsMainThreadTick(entity)) {
                // 装置/殖民地实体 tick 依赖主线程 getBlockEntity 与全局状态，dispatch 阶段直接路由主线程
                routeMainThreadEntity(level, entity);
                return;
            }
            int r = RegionLevel.regionId(entity.blockPosition());
            st.entityQueues[r].add(entity);
            perRegion[r]++;
            // authority-transfer counter: entity moved to a different region than last tick.
            Integer last = LAST_REGION.put(entity, r);
            if (last != null && last != r) {
                STATS.increment("cross.transfer");
            }
        });
        for (int r = 0; r < REGION_COUNT; r++) {
            STATS.add("entities.region" + r, perRegion[r]);
        }
        st.lastEntityDist = perRegion.clone();

        // 2. Parallel region ticks behind a per-phase latch.
        long gameTime = level.getGameTime();
        boolean applyNow = st.appliedTick != gameTime;
        if (applyNow) {
            st.appliedTick = gameTime;
        }
        runWorkers(level, applyNow, region -> {
            AtomicBoolean stopGate = RegionTickManager.currentDegradeStop();
            EntityBatchScheduler.Batch batch = stopGate != null
                    ? EntityBatchScheduler.begin(level, region, stopGate) : null;
            Entity entity;
            while ((entity = st.entityQueues[region].poll()) != null) {
                if (RegionTickManager.degradeStopRequested()) {
                    // B 组软降级:该 region 本轮未达成的实体刻丢弃(计 droppedWork)。
                    // 存活的实体下一 tick dispatch 重新入队 → 恰好被 tick 一次(被跳过的是本 tick);
                    // 已移除的实体直接丢弃。绝不遗留队列底部,否则 dispatch 重复入队会双 tick。
                    STATS.increment("barrier.droppedWork");
                    continue;
                }
                if (batch != null && EntityBatchScheduler.acceptable(level, entity)) {
                    batch.add(entity);
                    continue;
                }
                tickEntity(level, entity);
            }
            if (batch != null) {
                batch.flush();
            }
        });

        // 3. 玩家与拾取交互实体路由主线程 POST drain（有玩家维度已提交 worker 池，
        //    玩家 tick 必须主线程：容器菜单/网络包/Bukkit 玩家事件；region 完成后
        //    drainMainThreadEntityTicks 用 tickEntitySafely 执行，语义与 consumer.accept 等价）。
        long postJoinStart = Util.getNanos();  // §1.1: 量化 join 后主线程工作
        for (Entity entity : localTicks) {
            queueMainThreadEntityTick(level, entity);
        }
        if (PRTSFeaturesConfig.mainWastedMsTelemetry) {
            // §1.1: join 后主线程工作(player + localTicks)的 ms;重叠上界 = max(0, barrierWait - postJoin)。
            // 只量化不据此改序:player tick 需要看见同 tick region 已提交结果,顺序依赖不可破坏。
            long postJoinMs = (Util.getNanos() - postJoinStart) / 1_000_000L;
            long wastedMs = Math.max(0L, LAST_REGION_BARRIER_WAIT_MS - postJoinMs);
            STATS.record("main.wastedMs", wastedMs * 1_000_000L);
        }
        return true;
    }

    /**
     * region-parallel 关闭时的 vanilla forEach 兜底：维度 worker 上的装置/殖民地实体
     * 改排主线程队列（主线程 POST 阶段 drain），其余实体按原 consumer 执行。
     */
    public static void vanillaEntityTick(ServerLevel level, EntityTickList list, Consumer<Entity> consumer) {
        if (!DimensionTickManager.inDimensionTick()) {
            list.forEach(consumer);
            return;
        }
        List<Entity> local = new ArrayList<>();
        list.forEach(entity -> {
            if (needsMainThreadTick(entity)) {
                queueMainThreadEntityTick(level, entity);
            } else {
                local.add(entity);
            }
        });
        for (Entity entity : local) {
            // 维度 worker 上没有区域归属，但仍有 WorldAccessGuard 的违规归因需求：
            // 包一层实体上下文，enforce 模式的异常只中止当前实体 tick。
            CURRENT_ENTITY_CLASS.set(entity.getClass().getName());
            try {
                consumer.accept(entity);
            } catch (AccessViolation violation) {
                LOGGER.debug("[dimension-tick] worker access violation swallowed for {}: {}",
                        violation.ownerClassName(), violation.getMessage());
            } finally {
                CURRENT_ENTITY_CLASS.remove();
            }
        }
    }

    /** Drains and applies the journal entries collected for a region (on the region worker). */
    private static void applyCrossUpdates(ServerLevel level, int regionId) {
        int applied = state(level).journal.apply(level, regionId);
        if (applied > 0) {
            STATS.add("update.applied", applied);
        }
    }

    /** Submits one worker per region, drains the given phase work, waits for all. */
    private static void runWorkers(ServerLevel level, boolean applyNow, IntConsumer work) {
        // degraded level 的 region 阶段主线程串行执行(worker 不再参与,无 barrier)。
        if (isRegionDegraded(level)) {
            tickRegionRecovery(level);
            serialRegionPhase(level, applyNow, work);
            return;
        }
        IN_REGION_TICK.set(true);
        long[] regionTimings = new long[REGION_COUNT];  // S2.9.1: per-region timing
        // B 组:本会话软降级门,门置位后 worker 把队列剩余项取出即丢弃(计 barrier.droppedWork)。
        AtomicBoolean sessionDegrade = new AtomicBoolean(false);
        try {
            CountDownLatch latch = new CountDownLatch(REGION_COUNT);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            // 确定性模式：journal 在调度线程按 regionId 顺序统一应用（所有区域 session 启动前），
            // 应用顺序不再取决于各 region worker 的完成先后。默认关，行为不变。
            boolean globallyApplied = applyNow && PRTSFeaturesConfig.determinismMode;
            if (globallyApplied) {
                int applied = state(level).journal.applyAll(level);
                if (applied > 0) {
                    STATS.add("update.applied", applied);
                }
            }
            final boolean applyLocal = applyNow && !globallyApplied;
            for (int r = 0; r < REGION_COUNT; r++) {
                final int region = r;
                try {
                    REGION_POOL.execute(() -> {
                        REGION_CONTEXT.set(new RegionContext(region, level, sessionDegrade));
                        try {
                            long start = Util.getNanos();
                            if (applyLocal) {
                                applyCrossUpdates(level, region);
                            }
                            // Apply this region's async pathfinding results on the region
                            // worker before ticking, same-thread with entity ticks.
                            AsyncPathfindingManager.drainRegion(region, level.getGameTime());
                            long regionWorkStart = Util.getNanos();  // time just the work phase
                            work.accept(region);
                            regionTimings[region] = Util.getNanos() - regionWorkStart;
                            STATS.record("region" + region, Util.getNanos() - start);
                        } catch (Throwable t) {
                            failure.compareAndSet(null, t);
                        } finally {
                            REGION_CONTEXT.remove();
                            latch.countDown();
                        }
                    });
                } catch (Throwable t) {
                    // RejectedExecutionException etc.: count this region as done so the
                    // barrier can never hang, then surface the failure below.
                    failure.compareAndSet(null, t);
                    latch.countDown();
                }
            }
            // Measure barrier wait (main thread blocked for slowest region)
            long barrierStart = Util.getNanos();
            try {
                awaitRegionBarrier(latch, level, sessionDegrade);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for region ticks", e);
            }
            long barrierWaitNanos = Util.getNanos() - barrierStart;
            LAST_REGION_BARRIER_WAIT_MS = barrierWaitNanos / 1_000_000L;
            STATS.record("barrier.wait.ms", barrierWaitNanos / 1_000_000L);

            Throwable failure0 = failure.get();
            if (failure0 != null) {
                throw new RuntimeException("Exception ticking on region thread", failure0);
            }
            // 区域调度线程是主线程时（有玩家维度的 region 并行），worker 排队的实体新增
            // 立刻在主线程落地、同 tick 可见；维度 worker 调度的场景（无玩家维度）由
            // DimensionTickManager POST 阶段统一 drain。
            if (level.getServer() != null && level.getServer().isSameThread()) {
                drainMainThreadEntityAdds(level);
            }
            drainScheduleTasks();
            STATS.tick(level.getServer() == null ? 0 : level.getServer().getTickCount());
            EventBusStats.tickIfNeeded(level.getServer() == null ? 0 : level.getServer().getTickCount());
            // Save per-region timing for status display + 本维度累计（评估窗口）
            DimensionState ds = state(level);
            ds.lastRegionTimingNanos = regionTimings;
            for (int r = 0; r < REGION_COUNT; r++) {
                ds.regionTotalNanos[r] += regionTimings[r];
                ds.regionCount[r]++;
            }
        } finally {
            IN_REGION_TICK.set(false);
        }
    }

    /** degraded level 的 region 阶段串行执行:先应用 journal,再逐 region 执行 work。 */
    private static void serialRegionPhase(ServerLevel level, boolean applyNow, IntConsumer work) {
        try {
            if (applyNow) {
                int applied = state(level).journal.applyAll(level);
                if (applied > 0) {
                    STATS.add("update.applied", applied);
                }
            }
            for (int r = 0; r < REGION_COUNT; r++) {
                work.accept(r);
            }
        } finally {
            STATS.tick(level.getServer() == null ? 0 : level.getServer().getTickCount());
        }
    }

    /**
     * B 组:region barrier 等待(时间切片 join,docs/2026-08-27-region-parallel-barrier-idle-fix.md §2)。
     *
     * <p>主线程正常(未超预算)时与旧行为一致:barrierTimeoutMs 硬超时等待最慢 region 完成
     * (保持 barrier 完整性,同 chunk tick 责任唯一)。已超预算且 soft-degrade 开启时:
     * <ol>
     *   <li>以剩余预算等待;超时 = 该 region 未完成 → 记 {@code barrier.softDegrades}/{@code barrier.lateRegions}
     *       (droppedRegion 遥测),不抛异常;</li>
     *   <li>置本会话停止门,迟到 region worker 在当前实体/BE/方块刻后把队列剩余项取出即丢弃
     *       (计 {@code barrier.droppedWork} = 该 region 本轮被跳过的 work)。存活实体下一 tick 由
     *       dispatch 重新入队 → 恰好被 tick 一次;已移除实体直接丢。绝不遗留队列底部,
     *       否则 dispatch 重复入队会双 tick——这就是文档 §2 的"丢的是被跳过的那部分 tick",
     *       与低 TPS 省略 tick 同谱系;</li>
     *   <li>wind-down 超限仍回硬 barrier-timeout 等待(绝不带着在会话内的 worker 进入下一 phase;
     *       真卡死仍由 barrierTimeoutDump 兜底)。</li>
     * </ol>
     * ENFORCE 语义不破坏:worker 异常仍在 {@code failure} 上抛,软降级只针对"没做完"。</p>
     */
    private static void awaitRegionBarrier(CountDownLatch latch, ServerLevel level,
                                           AtomicBoolean sessionDegrade) throws InterruptedException {
        MinecraftServer server = level.getServer();
        long budgetMs = PRTSFeaturesConfig.barrierSoftDegrade ? barrierBudgetMs(server) : -1L;
        long wallStart = Util.getNanos();
        if (budgetMs < 0L) {
            if (!hardAwaitRegion(latch, level, PRTSFeaturesConfig.barrierTimeoutMs)) {
                throw new RuntimeException(DimensionTickManager.barrierTimeoutDump("region"));
            }
            return;
        }
        if (latch.await(budgetMs, TimeUnit.MILLISECONDS)) {
            return;
        }
        sessionDegrade.set(true);
        long late = latch.getCount();
        STATS.increment("barrier.softDegrades");
        STATS.add("barrier.lateRegions", late);
        if (latch.await(BARRIER_WIND_DOWN_MS, TimeUnit.MILLISECONDS)) {
            return;
        }
        LOGGER.warn("[PRTS-Barrier] soft-degrade: {} region(s) not winding down within {}ms "
                        + "(budget={}ms); awaiting barrier timeout to preserve single-writer semantics",
                late, BARRIER_WIND_DOWN_MS, budgetMs);
        long hardRemainingMs = PRTSFeaturesConfig.barrierTimeoutMs - ((Util.getNanos() - wallStart) / 1_000_000L);
        if (!hardAwaitRegion(latch, level, Math.max(1L, hardRemainingMs))) {
            throw new RuntimeException(DimensionTickManager.barrierTimeoutDump("region"));
        }
    }

    /** region 硬超时等待 + degrade 处理;仍卡死返回 false(绝不带着在跑的 worker 进下一 phase)。 */
    private static boolean hardAwaitRegion(CountDownLatch latch, ServerLevel level, long waitMs)
            throws InterruptedException {
        if (latch.await(waitMs, TimeUnit.MILLISECONDS)) {
            return true;
        }
        if (PRTSFeaturesConfig.barrierTimeoutAction == PRTSFeaturesConfig.BarrierTimeoutAction.CRASH) {
            return false;
        }
        DimensionTickManager.onHardTimeout("region");
        REGION_HARD_TIMEOUTS.incrementAndGet();
        DimensionTickManager.barrierTimeoutDump("region");
        LOGGER.error("[PRTS-Barrier] region hard timeout with degrade action: marking {} degraded, "
                + "waiting one more window before giving up", level.dimension().location());
        markRegionDegraded(level);
        return latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Vanilla forEach consumer semantics, run on the region worker. */
    private static void tickEntity(ServerLevel level, Entity entity) {
        // 装置/殖民地实体兜底：任何喂入区域队列的主线程实体改走主线程（正常路径已在 dispatch 路由）
        if (needsMainThreadTick(entity)) {
            routeMainThreadEntity(level, entity);
            return;
        }
        // 车辆载有装置实体乘客时车辆也必须主线程 tick——否则 tickPassenger 在 worker 上
        // 驱动装置（矿车装配站：装置骑矿车，worker 上 getBlockEntity 恒 null → 装置自毁）
        for (Entity passenger : entity.getPassengers()) {
            if (needsMainThreadTick(passenger)) {
                routeMainThreadEntity(level, entity);
                return;
            }
        }
        // 区块已卸载的实体跳过（vanilla 卸载时先移除实体，并行窗口内兜底）
        ChunkPos chunkPos = entity.chunkPosition();
        if (!((ServerChunkCacheRegionBridge) level.getChunkSource()).arclight$hasLiveChunk(chunkPos.x, chunkPos.z)) {
            return;
        }
        String className = entity.getClass().getName();
        CURRENT_ENTITY_CLASS.set(className);
        // Check if this tick is a probation attempt
        long tick = level.getServer() != null ? level.getServer().getTickCount() : 0L;
        boolean isProbation = ClassAffinityLedger.shouldProbation(className, tick);
        if (isProbation) {
            ClassAffinityLedger.enterProbation(className);
        }
        try {
            tickEntitySafely(level, entity);
        } catch (AccessViolation violation) {
            // enforce 模式：只中止当前实体的本次 tick（guard 已记录/限流日志），
            // 绝不让单个 mod 的违规升级为并行会话失败。
            LOGGER.debug("[region-tick] worker access violation swallowed for {}: {}",
                    violation.ownerClassName(), violation.getMessage());
        } catch (Throwable t) {
            // 任何异常都不允许从实体并行会话冒泡成服务器崩溃：
            // 该实体类本会话永久降级回主线程（含非世界访问类的 mod 竞态）。
            EntityAffinity.markUnsafe(className);
            LOGGER.error("[region-tick] entity worker tick failed for {}: {}",
                    className, t.toString());
        } finally {
            CURRENT_ENTITY_CLASS.remove();
            // Exit probation and check result
            if (isProbation) {
                boolean hadViolation = ClassAffinityLedger.exitProbation(className);
                if (hadViolation) {
                    ClassAffinityLedger.probationFailed(className, tick);
                } else {
                    ClassAffinityLedger.clearRouted(className, tick);
                }
            }
        }
    }

    /** 与 vanilla forEach consumer 等价的实体 tick 体，worker 与主线程 drain 共用。 */
    private static void tickEntitySafely(ServerLevel level, Entity entity) {
        if (entity.isRemoved()) {
            return;
        }
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                return;
            }
            // 仅断链清理；无条件调用会拆解 Create 装置实体（stopRiding 即 disassemble）
            entity.stopRiding();
        }
        // multipart 实体（末影龙）主体也必须 tick，部件由实体自身 tick 驱动
        level.tickNonPassenger(entity);
    }

    private static boolean isEmpty(ConcurrentLinkedQueue<?>[] queues) {
        for (ConcurrentLinkedQueue<?> q : queues) {
            if (!q.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Redstone family tracked by the cross-write telemetry, keyed by registry id. */
    private static final Set<ResourceLocation> REDSTONE_KEYS = Set.of(
            BuiltInRegistries.BLOCK.getKey(Blocks.REDSTONE_WIRE),
            BuiltInRegistries.BLOCK.getKey(Blocks.REPEATER),
            BuiltInRegistries.BLOCK.getKey(Blocks.COMPARATOR),
            BuiltInRegistries.BLOCK.getKey(Blocks.REDSTONE_TORCH),
            BuiltInRegistries.BLOCK.getKey(Blocks.REDSTONE_BLOCK),
            BuiltInRegistries.BLOCK.getKey(Blocks.OBSERVER),
            BuiltInRegistries.BLOCK.getKey(Blocks.TARGET),
            BuiltInRegistries.BLOCK.getKey(Blocks.DAYLIGHT_DETECTOR));

    /** Registry-key comparison: instance equality breaks when a mod swaps the registry entry. */
    private static boolean isRedstone(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null && REDSTONE_KEYS.contains(key);
    }

    /** One-line cross-write journal status for /servercore status (overworld). */
    public static String journalStatusText(net.minecraft.server.MinecraftServer server) {
        ServerLevel overworld = server != null ? server.overworld() : null;
        if (overworld == null) {
            return "no overworld";
        }
        return state(overworld).journal.statusText();
    }

    /** worker 实体新增排队遥测：deferred=排队数 drained=主线程落地数 expired=过期丢弃数。 */
    public static String entityAddStatusText() {
        return "deferred=%d drained=%d expired=%d".formatted(
                STATS.counterSum("entities.addDeferred"),
                STATS.counterSum("entities.addMainDrained"),
                STATS.counterSum("entities.addExpired"));
    }

    /** Per-region load distribution for /servercore status (overworld, last entity session). */
    public static String regionLoadStatusText(net.minecraft.server.MinecraftServer server) {
        ServerLevel overworld = server != null ? server.overworld() : null;
        if (overworld == null || !regionEnabled()) {
            return "n/a";
        }
        DimensionState st = state(overworld);
        int[] dist = st.lastEntityDist;
        long[] timings = st.lastRegionTimingNanos;
        if (dist == null || timings == null) {
            return "no data";
        }
        StringBuilder sb = new StringBuilder();
        int[] widths = RegionLevel.regionWidths();
        sb.append("stripes=").append(PRTSFeaturesConfig.unevenStripes ? "uneven" : "equal")
                .append(" widths=").append(java.util.Arrays.toString(widths)).append(" | ");
        int maxRegion = 0;
        long maxTime = 0;
        for (int i = 0; i < REGION_COUNT; i++) {
            if (timings[i] > maxTime) {
                maxTime = timings[i];
                maxRegion = i;
            }
        }
        for (int i = 0; i < REGION_COUNT; i++) {
            if (i > 0) sb.append(", ");
            sb.append("r").append(i).append(":").append(dist[i]).append("ent/")
                    .append(timings[i] / 1_000_000L).append("ms(w=").append(widths[i]).append(")");
            if (i == maxRegion) {
                sb.append("(MAX)");
            }
        }
        return sb.toString();
    }

    private static String blockEntityTypeKey(TickingBlockEntity ticker) {
        try {
            return ticker.getType();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String blockEntityTypeKey(BlockEntity be) {
        try {
            return blockEntityTypeKey(be.getType());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String blockEntityTypeKey(BlockEntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
        return key != null ? key.toString() : String.valueOf(type);
    }

    private record BlockTick(BlockPos pos, Block block) {
    }

    /** 会话内共享的软降级门:region worker 检查到置位后会在实体/BE/方块刻边界退出轮询。 */
    private record RegionContext(int regionId, ServerLevel level, AtomicBoolean degradeStop) {
    }

    @SuppressWarnings("rawtypes")
    private record ScheduleTask(LevelTicks owner, ScheduledTick<?> tick) {
    }
}
