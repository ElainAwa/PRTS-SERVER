/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Map<ServerLevel, ConcurrentLinkedQueue<BlockEntity>> MAIN_THREAD_BLOCK_ENTITIES = new WeakHashMap<>();

    private static final ThreadPoolExecutor REGION_POOL = new ThreadPoolExecutor(
            0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, REGION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<Entity>[] QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<CrossUpdate>[] CROSS_UPDATES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<BlockTick>[] BLOCK_TICK_QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<TickingBlockEntity>[] TE_TICK_QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
    /** LevelTicks.schedule tasks deferred from region workers, applied on the main thread. */
    private static final ConcurrentLinkedQueue<ScheduledTick<?>> SCHEDULE_TASKS = new ConcurrentLinkedQueue<>();

    static {
        for (int i = 0; i < REGION_COUNT; i++) {
            QUEUES[i] = new ConcurrentLinkedQueue<>();
            CROSS_UPDATES[i] = new ConcurrentLinkedQueue<>();
            BLOCK_TICK_QUEUES[i] = new ConcurrentLinkedQueue<>();
            TE_TICK_QUEUES[i] = new ConcurrentLinkedQueue<>();
        }
    }

    private static final AtomicBoolean CONFIGURED = new AtomicBoolean(false);

    private static final AtomicBoolean IN_REGION_TICK = new AtomicBoolean(false);
    private static final ThreadLocal<Integer> CURRENT_REGION = new ThreadLocal<>();

    /** Last game tick on which cross-region updates were applied (per-tick gate). */
    private static volatile long APPLIED_TICK = -1L;

    /** Last-seen region per entity (authority-transfer counter). */
    private static final Map<Entity, Integer> LAST_REGION = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Per-region entity dispatch snapshot (auto-scale input; replaced atomically). */
    private static volatile int[] LAST_ENTITY_DIST = new int[REGION_COUNT];

    /** Auto-scale state: consecutive low-load periods, last evaluation tick, last cross.read. */
    private static int LOW_PERIODS = 0;
    private static long LAST_EVAL_TICK = -1L;
    private static long LAST_CROSS_READ = 0L;
    private static long LAST_TICKS = 0L;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[region-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("cross", "block", "redstone", "redstoneBoundary", "transfer", "read")
            .group("update", "blockTicks", "teTicks", "applied")
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
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<Entity>[] q = new ConcurrentLinkedQueue[n];
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<CrossUpdate>[] cu = new ConcurrentLinkedQueue[n];
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<BlockTick>[] bt = new ConcurrentLinkedQueue[n];
        @SuppressWarnings("unchecked")
        ConcurrentLinkedQueue<TickingBlockEntity>[] te = new ConcurrentLinkedQueue[n];
        for (int i = 0; i < n; i++) {
            q[i] = new ConcurrentLinkedQueue<>();
            cu[i] = new ConcurrentLinkedQueue<>();
            bt[i] = new ConcurrentLinkedQueue<>();
            te[i] = new ConcurrentLinkedQueue<>();
            STATS.ensureTimer("region" + i);
            STATS.ensureGroupMember("entities", "region" + i);
        }
        QUEUES = q;
        CROSS_UPDATES = cu;
        BLOCK_TICK_QUEUES = bt;
        TE_TICK_QUEUES = te;
        LAST_ENTITY_DIST = new int[n];
        LOGGER.info("[region-tick] region parallelism reconfigured: count={} (perRegion={} chunks/region)",
                n, RegionLevel.STRIPE_WIDTH / n);
    }

    /**
     * Periodically evaluates region load and adjusts the region count between the
     * configured min and max. Called after tickChildren when all workers have latched.
     */
    public static void evaluateAutoScale(int serverTick) {
        if (!regionEnabled() || !PRTSFeaturesConfig.regionAutoScale) {
            return;
        }
        long intervalTicks = Math.max(20L, PRTSFeaturesConfig.regionScaleIntervalSeconds * 20L);
        if (serverTick - LAST_EVAL_TICK < intervalTicks) {
            return;
        }
        LAST_EVAL_TICK = serverTick;
        int n = REGION_COUNT;
        double maxAvg = 0.0;
        int active = 0;
        int[] dist = LAST_ENTITY_DIST;
        for (int i = 0; i < n; i++) {
            double avg = STATS.avgMillis("region" + i);
            if (avg > maxAvg) maxAvg = avg;
            if (i < dist.length && dist[i] > 0) active++;
        }
        long crossNow = STATS.counterSum("cross.read");
        long ticksNow = STATS.counterSum("ticks");
        long crossDelta = crossNow - LAST_CROSS_READ;
        long ticksDelta = Math.max(1L, ticksNow - LAST_TICKS);
        LAST_CROSS_READ = crossNow;
        LAST_TICKS = ticksNow;
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
            LOW_PERIODS = 0;
        } else if (maxAvg < low && active <= n / 2) {
            if (++LOW_PERIODS >= PRTSFeaturesConfig.regionScaleStablePeriods && n > min) {
                target = n / 2;
                reason = String.format("low-load maxAvg=%.1fms active=%d periods=%d", maxAvg, active, LOW_PERIODS);
                LOW_PERIODS = 0;
            }
        } else {
            LOW_PERIODS = 0;
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
        Integer r = CURRENT_REGION.get();
        return r == null ? -1 : r;
    }

    /** True when the current thread is a region worker. */
    public static boolean isRegionWorker() {
        return currentRegion() >= 0;
    }

    /** 把一个方块实体排到主线程队列（下次主线程 PRE 阶段执行其 tick）。 */
    public static void queueMainThreadBlockEntity(BlockEntity be) {
        if (be.getLevel() instanceof ServerLevel level) {
            MAIN_THREAD_BLOCK_ENTITIES.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(be);
        }
    }

    /** 主线程 PRE 阶段调用：执行本维度排队的主线程方块实体 tick。 */
    public static void drainMainThreadBlockEntities(ServerLevel level) {
        ConcurrentLinkedQueue<BlockEntity> queue = MAIN_THREAD_BLOCK_ENTITIES.remove(level);
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
        CROSS_UPDATES[target].add(new CrossUpdate(pos, state, flags));
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
        int r = RegionLevel.regionId(pos);
        BLOCK_TICK_QUEUES[r].add(new BlockTick(pos, block));
        STATS.increment("update.blockTicks");
    }

    /** Collects a ticking block entity into the owning region's queue. */
    public static void collectBlockEntityTick(TickingBlockEntity ticker) {
        int r = RegionLevel.regionId(ticker.getPos());
        TE_TICK_QUEUES[r].add(ticker);
        STATS.increment("update.teTicks");
    }

    /** Defers a LevelTicks.schedule call to the main thread (LevelTicks is not thread-safe). */
    public static void collectScheduleTick(ScheduledTick<?> tick) {
        SCHEDULE_TASKS.add(tick);
    }

    /** Applies deferred scheduling tasks on the main thread after a region session. */
    private static void drainScheduleTasks(ServerLevel level) {
        ScheduledTick<?> t;
        while ((t = SCHEDULE_TASKS.poll()) != null) {
            if (t.type() instanceof Block) {
                level.getBlockTicks().schedule((ScheduledTick<Block>) t);
            } else {
                level.getFluidTicks().schedule((ScheduledTick<Fluid>) t);
            }
        }
    }

    /** Session 1: runs collected scheduled block ticks on region workers (per-tick gate for cross-region updates). */
    public static boolean runBlockTickPhase(ServerLevel level) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        ensureConfigured();
        long gameTime = level.getGameTime();
        boolean applyNow = APPLIED_TICK != gameTime;
        if (applyNow) {
            APPLIED_TICK = gameTime;
        }
        if (!applyNow && isEmpty(BLOCK_TICK_QUEUES)) {
            return false;
        }
        if (applyNow && isEmpty(BLOCK_TICK_QUEUES) && isEmpty(CROSS_UPDATES)) {
            return false;
        }
        runWorkers(level, applyNow, region -> {
            BlockTick bt;
            while ((bt = BLOCK_TICK_QUEUES[region].poll()) != null) {
                ((ServerLevelRegionBlockTickAccess) level).arclight$tickBlock(bt.pos(), bt.block());
            }
        });
        return true;
    }

    /** Session 3: runs collected block-entity ticks on region workers. */
    public static boolean runBlockEntityTickPhase(ServerLevel level) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        ensureConfigured();
        if (isEmpty(TE_TICK_QUEUES)) {
            return false;
        }
        long gameTime = level.getGameTime();
        boolean applyNow = APPLIED_TICK != gameTime;
        if (applyNow) {
            APPLIED_TICK = gameTime;
        }
        runWorkers(level, applyNow, region -> {
            TickingBlockEntity te;
            while ((te = TE_TICK_QUEUES[region].poll()) != null) {
                te.tick();
            }
        });
        return true;
    }

    /** Session 2 (entity tick): dispatches the entity list by region and ticks on region workers. */
    public static boolean dispatchAndTick(ServerLevel level, EntityTickList list) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        ensureConfigured();
        STATS.increment("ticks");

        // 1. Dispatch (dimension worker; no concurrent write to the tick list here).
        int[] perRegion = new int[REGION_COUNT];
        list.forEach(entity -> {
            int r = RegionLevel.regionId(entity.blockPosition());
            QUEUES[r].add(entity);
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
        LAST_ENTITY_DIST = perRegion.clone();

        // 2. Parallel region ticks behind a per-phase latch.
        long gameTime = level.getGameTime();
        boolean applyNow = APPLIED_TICK != gameTime;
        if (applyNow) {
            APPLIED_TICK = gameTime;
        }
        runWorkers(level, applyNow, region -> {
            Entity entity;
            while ((entity = QUEUES[region].poll()) != null) {
                tickEntity(level, entity);
            }
        });
        return true;
    }

    /** Drains and applies the update set collected for a region (on the region worker). */
    private static void applyCrossUpdates(ServerLevel level, int regionId) {
        CrossUpdate u;
        int applied = 0;
        while ((u = CROSS_UPDATES[regionId].poll()) != null) {
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
        CountDownLatch latch = new CountDownLatch(REGION_COUNT);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int r = 0; r < REGION_COUNT; r++) {
            final int region = r;
            REGION_POOL.execute(() -> {
                CURRENT_REGION.set(region);
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
                    CURRENT_REGION.remove();
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException(DimensionTickManager.barrierTimeoutDump("region"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for region ticks", e);
        }
        IN_REGION_TICK.set(false);

        Throwable failure0 = failure.get();
        if (failure0 != null) {
            throw new RuntimeException("Exception ticking on region thread", failure0);
        }
        drainScheduleTasks(level);
        STATS.tick(level.getServer() == null ? 0 : level.getServer().getTickCount());
    }

    /** Vanilla forEach consumer semantics, run on the region worker. */
    private static void tickEntity(ServerLevel level, Entity entity) {
        Entity vehicle = entity.getVehicle();
        if (vehicle != null && !vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
            return;
        }
        entity.stopRiding();
        Entity[] parts = ((EntityBridge) entity).bridge$forge$getParts();
        if (!entity.isRemoved() && (parts == null || parts.length == 0)) {
            level.tickNonPassenger(entity);
            // NeoForge moved AI scheduling (goalSelector/targetSelector/brain) out
            // of Entity.tick into serverAiStep(), invoked only by the main-thread
            // tick consumer. The region worker must run it explicitly or mobs freeze.
            if (entity instanceof LivingEntity living) {
                ((LivingEntityServerAiStepAccess) living).arclight$invokerServerAiStep();
            }
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
}
