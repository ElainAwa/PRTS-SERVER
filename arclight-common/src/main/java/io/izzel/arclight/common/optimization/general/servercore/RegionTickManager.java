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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;
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
 * PRTS region-level tick parallelism (P3, AI-created).
 *
 * <p>Runs the overworld's block-tick / entity-tick / block-entity-tick phases on
 * {@link #REGION_COUNT} region worker threads instead of the dimension worker.
 * Each phase is a latch-synchronized session dispatched by chunk-column ownership
 * ({@link RegionLevel#regionId}); within a region the vanilla order is preserved
 * (block tick session, then entity session, then block-entity session — review
 * v03 plan A). Only one session is active at any time, so the dimension worker's
 * random-tick phase never overlaps a region session.</p>
 *
 * <p>Update-set protocol (design docs/parallel-phase3-region-parallelism-v03.md,
 * v04): a region worker writing a block outside its own region collects the write
 * into the target region's queue; the next tick that region's worker applies the
 * batch at the start of its first session (per-tick gate) with the original flags,
 * so neighbor updates re-trigger inline and any cross-region spill is collected
 * again for the following tick (batch-apply-then-trigger, at most one extra tick
 * per crossing). {@code cross.block/redstone} and {@code update.applied} counters
 * are the P3 instrumentation dashboard.</p>
 */
public final class RegionTickManager {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");
    public static final String REGION_THREAD_PREFIX = "PRTS-RegionTick-";
    private static final int REGION_COUNT = RegionLevel.DEFAULT_REGION_COUNT;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();
    private static final ThreadPoolExecutor REGION_POOL = new ThreadPoolExecutor(
            0, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, REGION_THREAD_PREFIX + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<Entity>[] QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<CrossUpdate>[] CROSS_UPDATES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<BlockTick>[] BLOCK_TICK_QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<TickingBlockEntity>[] TE_TICK_QUEUES = new ConcurrentLinkedQueue[REGION_COUNT];
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

    private static final AtomicBoolean IN_REGION_TICK = new AtomicBoolean(false);
    private static final ThreadLocal<Integer> CURRENT_REGION = new ThreadLocal<>();

    /** Last game tick on which cross-region updates were applied (per-tick gate). */
    private static volatile long APPLIED_TICK = -1L;

    /** Last-seen region per entity (authority-transfer counter). */
    private static final Map<Entity, Integer> LAST_REGION = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[region-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("entities", "region0", "region1")
            .group("cross", "block", "redstone", "redstoneBoundary", "transfer", "read")
            .group("update", "blockTicks", "teTicks", "applied")
            .timer("region0").timer("region1")
            .build();

    private RegionTickManager() {
    }

    /** True on a region tick worker thread (P3). */
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

    /** True when the region-parallel feature is enabled (PRTS prts-features.yml). */
    public static boolean regionEnabled() {
        return PRTSFeaturesConfig.parallelRegion;
    }

    /** True when a region worker is about to write a block outside its own region. */
    public static boolean isCrossWrite(BlockPos pos) {
        int r = currentRegion();
        return r >= 0 && !RegionLevel.isAuthoritative(pos, r);
    }

    /**
     * Cross-region block read counter (P3 slice 3, v01 §3.5 "cross-read is the
     * measuring instrument"). A region worker reading a block outside its own
     * region is counted — the read itself is safe (PalettedContainer is
     * lock-free-read / locked-write), but the frequency exposes how well the
     * stripe boundary matches real coupling.
     */
    public static void countCrossRead(BlockPos pos) {
        int r = currentRegion();
        if (r >= 0 && !RegionLevel.isAuthoritative(pos, r)) {
            STATS.increment("cross.read");
        }
    }

    /**
     * Called from the Level.setBlock interception (LevelMixin_RegionCrossWrite)
     * on a region worker for a cross-region write: collect into the target
     * region's update queue, applied next tick by that region's worker.
     */
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

    /**
     * True if the column is inside a region's 1-chunk boundary band (P3 Phase 4,
     * v09 §3.2). The stripe boundary between regions lies every STRIPE_WIDTH
     * columns; the two columns adjacent to the boundary (chunkX % 8 == 7 and
     * the next stripe's chunkX % 8 == 0, i.e. group edge) form the band. Used to
     * verify the v01 §5 threshold: cross-region redstone traffic concentrated in
     * the boundary band supports the "no redstone graph solver" decision.
     */
    private static boolean isBoundaryColumn(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int group = Math.floorMod(chunkX, RegionLevel.STRIPE_WIDTH);
        return group == RegionLevel.STRIPE_WIDTH - 1 || group == 0;
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

    /**
     * Called from the LevelTicks.schedule interception on a region worker:
     * defers the scheduling to the main thread (LevelTicks is not thread-safe).
     */
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

    /**
     * Session 1: runs the collected scheduled block ticks on region workers.
     * Called from the ServerChunkCache.tick redirect before the random-tick phase;
     * also the first session of each game tick, so cross-region updates collected
     * last tick are applied here (per-tick gate).
     */
    public static boolean runBlockTickPhase(ServerLevel level) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
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

    /**
     * Session 3: runs the collected block-entity ticks on region workers.
     * Called at the RETURN of Level.tickBlockEntities.
     */
    public static boolean runBlockEntityTickPhase(ServerLevel level) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
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

    /**
     * Session 2 (entity tick): dispatches the entity list by region and runs the
     * ticks on region workers; returns true when the region path handled the phase.
     */
    public static boolean dispatchAndTick(ServerLevel level, EntityTickList list) {
        if (!regionEnabled() || level.dimension() != Level.OVERWORLD) {
            return false;
        }
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
            STATS.add("entities." + (r == 0 ? "region0" : "region1"), perRegion[r]);
        }

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
                    // P3 slice 4 (v08): apply this region's async pathfinding results
                    // on the region worker before ticking, same-thread with entity ticks.
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
            latch.await();
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
            // NeoForge 1.21.1 moved AI scheduling (goalSelector/targetSelector/
            // brain) out of Entity.tick (now just baseTick) into serverAiStep(),
            // invoked only by the main-thread tick consumer. The region worker
            // path must run it explicitly or mobs freeze (same-thread with tick).
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
