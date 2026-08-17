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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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

    /** 维度并行期间收集、由主线程统一执行的方块实体 tick，按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<TickingBlockEntity>> MAIN_THREAD_TE_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 维度并行期间收集、由主线程统一执行的实体 tick（Create 装置实体），按维度分队列。 */
    private static final Map<ServerLevel, LinkedBlockingQueue<Entity>> MAIN_THREAD_ENTITY_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 主线程实体判定缓存（类名前缀走查超类链）。 */
    private static final ConcurrentHashMap<Class<?>, Boolean> MAIN_THREAD_ENTITY_CACHE = new ConcurrentHashMap<>();

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
        // Auto-scale per-dimension counters (only overworld drives the decision).
        int lowPeriods = 0;
        long lastEvalTick = -1L;
        long lastCrossRead = 0L;
        long lastTicks = 0L;

        DimensionState(int n) {
            this.entityQueues = newQueues(n);
            this.journal = new WorldWriteJournal(n, PRTSFeaturesConfig.journalMaxPerRegion);
            this.blockTickQueues = newQueues(n);
            this.teTickQueues = newQueues(n);
            this.lastEntityDist = new int[n];
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
            .group("update", "blockTicks", "teTicks", "applied", "teMainTicks")
            .timer("entities.mainThreadMs")
            .counter("colony.ticks")
            .timer("colony.ms")
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

    /** Rebuilds the per-region queues for a new region count (startup only). */
    static synchronized void reconfigure(int n) {
        REGION_COUNT = n;
        // N=16 时把条纹宽从 8 扩到 16，保证每区仍为整数条 chunk 列（N<=8 保持 8，行为不变）。
        RegionLevel.setStripeWidth(n);
        for (int i = 0; i < n; i++) {
            STATS.ensureTimer("region" + i);
            STATS.ensureGroupMember("entities", "region" + i);
        }
        STATS.ensureGroupMember("entities", "mainThread");
        // Rebuild queues for every already-seen dimension. reconfigure runs on the
        // main thread after workers have latched, so no worker holds a stale array.
        DIMENSION_STATES.forEach((level, st) -> DIMENSION_STATES.put(level, new DimensionState(n)));
        LOGGER.info("[region-tick] region parallelism reconfigured: count={} (perRegion={} chunks/region)",
                n, RegionLevel.STRIPE_WIDTH / n);
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
        if (serverTick - st.lastEvalTick < intervalTicks) {
            return;
        }
        st.lastEvalTick = serverTick;
        int n = REGION_COUNT;
        double maxAvg = 0.0;
        int active = 0;
        int[] dist = st.lastEntityDist;
        for (int i = 0; i < n; i++) {
            double avg = STATS.avgMillis("region" + i);
            if (avg > maxAvg) maxAvg = avg;
            if (i < dist.length && dist[i] > 0) active++;
        }
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
        if (maxAvg > high && n < max && active >= n && crossRatio <= crossBudget) {
            target = n * 2;
            reason = String.format("high-load maxAvg=%.1fms active=%d crossRatio=%.3f", maxAvg, active, crossRatio);
            st.lowPeriods = 0;
        } else if (maxAvg < low && active <= n / 2) {
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
                BlockEntityTickStats.record(blockEntityTypeKey(be), Util.getNanos() - start, be.getBlockPos());
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
                BlockEntityTickStats.record(blockEntityTypeKey(ticker), Util.getNanos() - start, ticker.getPos());
            }
        }
    }

    /** 维度 worker 调用：把实体 tick 排到本维度的主线程队列。 */
    public static void queueMainThreadEntityTick(ServerLevel level, Entity entity) {
        LinkedBlockingQueue<Entity> queue;
        synchronized (MAIN_THREAD_ENTITY_TICKS) {
            queue = MAIN_THREAD_ENTITY_TICKS.computeIfAbsent(level, k -> new LinkedBlockingQueue<>());
        }
        queue.add(entity);
    }

    /** 主线程 POST 阶段调用：执行本维度排队的主线程实体 tick。 */
    public static void drainMainThreadEntityTicks(ServerLevel level) {
        LinkedBlockingQueue<Entity> queue;
        synchronized (MAIN_THREAD_ENTITY_TICKS) {
            queue = MAIN_THREAD_ENTITY_TICKS.remove(level);
        }
        if (queue == null) {
            return;
        }
        long start = Util.getNanos();
        java.util.Collection<Entity> batch = new ArrayList<>();
        queue.drainTo(batch);
        for (Entity entity : batch) {
            boolean colony = entity.getClass().getName().startsWith("com.minecolonies.");
            long entityStart = colony ? Util.getNanos() : 0L;
            try {
                tickEntitySafely(level, entity);
                STATS.increment("entities.mainThread");
                if (colony) {
                    STATS.increment("colony.ticks");
                    STATS.record("colony.ms", Util.getNanos() - entityStart);
                }
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread entity tick failed at {}: {}",
                        entity.blockPosition(), t.toString());
            }
        }
        STATS.record("entities.mainThreadMs", Util.getNanos() - start);
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
        // 3) 种子前缀：v0.35 沉淀的手工知识，作为学习器的初始规则
        Boolean seeded = MAIN_THREAD_ENTITY_CACHE.get(c);
        if (seeded == null) {
            seeded = matchesAnyPrefix(c, MAIN_THREAD_ENTITY_PREFIXES);
            MAIN_THREAD_ENTITY_CACHE.put(c, seeded);
        }
        if (seeded) {
            return true;
        }
        // 4) 运行时学习：Phase 2 违规窗口阈值路由（下 tick 起生效）
        if (!"auto".equals(PRTSFeaturesConfig.mainThreadRouting)) {
            return false;
        }
        long tick = entity.level().getServer() != null ? entity.level().getServer().getTickCount() : 0L;
        return ClassAffinityLedger.shouldRouteMainThread(c.getName(), tick);
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
        return PRTSFeaturesConfig.parallelRegion;
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
        state(level).journal.submit(target, new WorldWriteJournal.Entry(pos, state, flags, tick, source));
        STATS.increment("cross.block");
        if (isRedstone(state.getBlock())) {
            STATS.increment("cross.redstone");
            if (isBoundaryColumn(pos)) {
                STATS.increment("cross.redstoneBoundary");
            }
        }
    }

    /** True if the column is inside a region's 1-chunk boundary band. */
    private static boolean isBoundaryColumn(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int group = Math.floorMod(chunkX, RegionLevel.STRIPE_WIDTH);
        int perRegion = RegionLevel.STRIPE_WIDTH / regionCount();
        return group % perRegion == perRegion - 1 || group % perRegion == 0;
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

    /** Applies deferred scheduling tasks on the main thread after a region session. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void drainScheduleTasks() {
        ScheduleTask task;
        while ((task = SCHEDULE_TASKS.poll()) != null) {
            try {
                task.owner().schedule(task.tick());
            } catch (Throwable t) {
                LOGGER.warn("[region-tick] deferred schedule failed: {}", t.toString());
            }
        }
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
            // 并行方块 tick 会撕裂网络节点引用，此处回退当前线程串行（每维度单线程）
            for (int r = 0; r < st.blockTickQueues.length; r++) {
                BlockTick bt;
                while ((bt = st.blockTickQueues[r].poll()) != null) {
                    ((ServerLevelRegionBlockTickAccess) level).arclight$tickBlock(bt.pos(), bt.block());
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
                    BlockEntityTickStats.record(typeKey, Util.getNanos() - start, te.getPos());
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
            Entity entity;
            while ((entity = st.entityQueues[region].poll()) != null) {
                tickEntity(level, entity);
            }
        });

        // 3. Tick players and pickup-interactive entities on the current thread.
        for (Entity entity : localTicks) {
            if (!(entity instanceof ServerPlayer)) {
                // 区块已卸载时跳过：维度 worker 无法补生成，vanilla 此时已移除实体
                ChunkPos cp = entity.chunkPosition();
                if (!((ServerChunkCacheRegionBridge) level.getChunkSource()).arclight$hasLiveChunk(cp.x, cp.z)) {
                    continue;
                }
            }
            consumer.accept(entity);
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
        IN_REGION_TICK.set(true);
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
                        REGION_CONTEXT.set(new RegionContext(region, level));
                        try {
                            long start = Util.getNanos();
                            if (applyLocal) {
                                applyCrossUpdates(level, region);
                            }
                            // Apply this region's async pathfinding results on the region
                            // worker before ticking, same-thread with entity ticks.
                            AsyncPathfindingManager.drainRegion(region, level.getGameTime());
                            work.accept(region);
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
            try {
                if (!latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS)) {
                    throw new RuntimeException(DimensionTickManager.barrierTimeoutDump("region"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for region ticks", e);
            }

            Throwable failure0 = failure.get();
            if (failure0 != null) {
                throw new RuntimeException("Exception ticking on region thread", failure0);
            }
            drainScheduleTasks();
            STATS.tick(level.getServer() == null ? 0 : level.getServer().getTickCount());
            EventBusStats.tickIfNeeded(level.getServer() == null ? 0 : level.getServer().getTickCount());
        } finally {
            IN_REGION_TICK.set(false);
        }
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
        CURRENT_ENTITY_CLASS.set(entity.getClass().getName());
        try {
            tickEntitySafely(level, entity);
        } catch (AccessViolation violation) {
            // enforce 模式：只中止当前实体的本次 tick（guard 已记录/限流日志），
            // 绝不让单个 mod 的违规升级为并行会话失败。
            LOGGER.debug("[region-tick] worker access violation swallowed for {}: {}",
                    violation.ownerClassName(), violation.getMessage());
        } finally {
            CURRENT_ENTITY_CLASS.remove();
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

    private static boolean isRedstone(Block block) {
        return block == Blocks.REDSTONE_WIRE || block == Blocks.REPEATER || block == Blocks.COMPARATOR
                || block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_BLOCK || block == Blocks.OBSERVER
                || block == Blocks.TARGET || block == Blocks.DAYLIGHT_DETECTOR;
    }

    /** One-line cross-write journal status for /servercore status (overworld). */
    public static String journalStatusText(net.minecraft.server.MinecraftServer server) {
        ServerLevel overworld = server != null ? server.overworld() : null;
        if (overworld == null) {
            return "no overworld";
        }
        return state(overworld).journal.statusText();
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

    private record RegionContext(int regionId, ServerLevel level) {
    }

    @SuppressWarnings("rawtypes")
    private record ScheduleTask(LevelTicks owner, ScheduledTick<?> tick) {
    }
}
