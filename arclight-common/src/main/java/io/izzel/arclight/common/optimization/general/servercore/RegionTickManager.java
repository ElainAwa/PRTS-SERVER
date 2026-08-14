/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private static final Map<ServerLevel, ConcurrentLinkedQueue<BlockEntity>> MAIN_THREAD_BLOCK_ENTITIES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** 维度并行期间收集、由主线程统一执行的方块实体 tick，按维度分队列。 */
    private static final Map<ServerLevel, ConcurrentLinkedQueue<TickingBlockEntity>> MAIN_THREAD_TE_TICKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final ThreadPoolExecutor REGION_POOL = new ThreadPoolExecutor(
            0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, REGION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    /** LevelTicks.schedule tasks deferred from region workers, applied on the main thread. */
    private static final ConcurrentLinkedQueue<ScheduleTask> SCHEDULE_TASKS = new ConcurrentLinkedQueue<>();

    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    private static final AtomicBoolean IN_REGION_TICK = new AtomicBoolean(false);
    private static final ThreadLocal<RegionContext> REGION_CONTEXT = new ThreadLocal<>();

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
        final ConcurrentLinkedQueue<CrossUpdate>[] crossUpdates;
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
            this.crossUpdates = newQueues(n);
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
        for (int i = 0; i < n; i++) {
            STATS.ensureTimer("region" + i);
            STATS.ensureGroupMember("entities", "region" + i);
        }
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

    /** 把一个方块实体排到主线程队列（下次主线程 PRE 阶段执行其 tick）。 */
    public static void queueMainThreadBlockEntity(BlockEntity be) {
        if (be.getLevel() instanceof ServerLevel level) {
            ConcurrentLinkedQueue<BlockEntity> queue;
            synchronized (MAIN_THREAD_BLOCK_ENTITIES) {
                queue = MAIN_THREAD_BLOCK_ENTITIES.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>());
            }
            queue.add(be);
        }
    }

    /** 主线程 PRE 阶段调用：执行本维度排队的主线程方块实体 tick。 */
    public static void drainMainThreadBlockEntities(ServerLevel level) {
        ConcurrentLinkedQueue<BlockEntity> queue;
        synchronized (MAIN_THREAD_BLOCK_ENTITIES) {
            queue = MAIN_THREAD_BLOCK_ENTITIES.remove(level);
        }
        if (queue == null) {
            return;
        }
        BlockEntity be;
        while ((be = queue.poll()) != null) {
            try {
                // 方块实体子类各自定义 tick()（BlockEntity 基类无此方法），反射调用
                be.getClass().getMethod("tick").invoke(be);
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block entity tick failed at {}: {}",
                        be.getBlockPos(), t.toString());
            }
        }
    }

    /** 维度 worker 调用：把方块实体 tick 排到本维度的主线程队列。 */
    public static void queueMainThreadBlockEntityTick(ServerLevel level, TickingBlockEntity ticker) {
        ConcurrentLinkedQueue<TickingBlockEntity> queue;
        synchronized (MAIN_THREAD_TE_TICKS) {
            queue = MAIN_THREAD_TE_TICKS.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>());
        }
        queue.add(ticker);
    }

    /** 主线程 POST 阶段调用：执行本维度排队的主线程方块实体 tick。 */
    public static void drainMainThreadBlockEntityTicks(ServerLevel level) {
        ConcurrentLinkedQueue<TickingBlockEntity> queue;
        synchronized (MAIN_THREAD_TE_TICKS) {
            queue = MAIN_THREAD_TE_TICKS.remove(level);
        }
        if (queue == null) {
            return;
        }
        TickingBlockEntity ticker;
        while ((ticker = queue.poll()) != null) {
            try {
                ticker.tick();
                STATS.increment("update.teMainTicks");
            } catch (Throwable t) {
                LOGGER.error("[region-tick] main-thread block entity tick failed at {}: {}",
                        ticker.getPos(), t.toString());
            }
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

    /** Collects a cross-region write into the target region's queue (Level.setBlock interception). */
    public static void collectCrossWrite(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        int target = RegionLevel.regionId(pos);
        state(level).crossUpdates[target].add(new CrossUpdate(pos, state, flags));
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
        if (applyNow && isEmpty(st.blockTickQueues) && isEmpty(st.crossUpdates)) {
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
                for (int r = 0; r < st.crossUpdates.length; r++) {
                    CrossUpdate u;
                    while ((u = st.crossUpdates[r].poll()) != null) {
                        level.setBlock(u.pos(), u.state(), u.flags());
                    }
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
                te.tick();
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

    /** Drains and applies the update set collected for a region (on the region worker). */
    private static void applyCrossUpdates(ServerLevel level, int regionId) {
        CrossUpdate u;
        int applied = 0;
        ConcurrentLinkedQueue<CrossUpdate> updates = state(level).crossUpdates[regionId];
        while ((u = updates.poll()) != null) {
            try {
                level.setBlock(u.pos(), u.state(), u.flags());
                applied++;
            } catch (Throwable t) {
                LOGGER.warn("[region-tick] cross-update apply failed at {}: {}", u.pos(), t.toString());
            }
        }
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
            for (int r = 0; r < REGION_COUNT; r++) {
                final int region = r;
                try {
                    REGION_POOL.execute(() -> {
                        REGION_CONTEXT.set(new RegionContext(region, level));
                        try {
                            long start = Util.getNanos();
                            if (applyNow) {
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
        } finally {
            IN_REGION_TICK.set(false);
        }
    }

    /** Vanilla forEach consumer semantics, run on the region worker. */
    private static void tickEntity(ServerLevel level, Entity entity) {
        // 区块已卸载的实体跳过（vanilla 卸载时先移除实体，并行窗口内兜底）
        ChunkPos chunkPos = entity.chunkPosition();
        if (!((ServerChunkCacheRegionBridge) level.getChunkSource()).arclight$hasLiveChunk(chunkPos.x, chunkPos.z)) {
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
        Entity[] parts = ((EntityBridge) entity).bridge$forge$getParts();
        if (!entity.isRemoved() && (parts == null || parts.length == 0)) {
            level.tickNonPassenger(entity);
        }
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

    private record CrossUpdate(BlockPos pos, BlockState state, int flags) {
    }

    private record BlockTick(BlockPos pos, Block block) {
    }

    private record RegionContext(int regionId, ServerLevel level) {
    }

    @SuppressWarnings("rawtypes")
    private record ScheduleTask(LevelTicks owner, ScheduledTick<?> tick) {
    }
}
