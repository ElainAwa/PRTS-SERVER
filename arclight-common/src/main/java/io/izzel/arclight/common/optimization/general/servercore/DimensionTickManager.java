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
            });

    private static final ConcurrentLinkedQueue<PendingTransfer> TRANSFERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingEvent> PENDING_EVENTS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean IN_DIMENSION_TICK = new AtomicBoolean(false);

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[dimension-tick]")
            .intervalTicks(600)
            .counter("ticks")
            .group("pendingTransfer", "player", "entity", "cancelled", "dropped")
            .timer("overworld").timer("nether").timer("end").timer("other")
            .build();

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
            RegionTickManager.drainMainThreadBlockEntities(level);
        }

        // 2. Parallel ticks on worker threads, behind a per-tick barrier.
        IN_DIMENSION_TICK.set(true);
        CountDownLatch latch = new CountDownLatch(n);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            final ParallelTickUnit unit = units[i];
            POOL.execute(() -> {
                try {
                    unit.tick(hasTimeLeft);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            if (!latch.await(PRTSFeaturesConfig.barrierTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException(barrierTimeoutDump("dimension"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for parallel ticks", e);
        }
        IN_DIMENSION_TICK.set(false);

        // 3. Propagate a worker failure (crashes the server like vanilla would).
        Throwable failure0 = failure.get();
        if (failure0 != null) {
            throw new ReportedExceptionWrapping(failure0);
        }

        // 4. Post phase (main thread) + perWorldTickTimes in vanilla format.
        for (int i = 0; i < n; i++) {
            ServerLevel level = units[i].level();
            POST.fire(level, hasTimeLeft);
            long elapsed = Util.getNanos() - startNanos[i];
            perWorldTickTimes.computeIfAbsent(level.dimension(), k -> new long[100])[tickCount % 100] = elapsed;
            STATS.record(timerName(level.dimension()), elapsed);
        }

        // 5. Execute deferred cross-dimension transfers on the main thread.
        drainTransfers();

        // 6. Fire deferred entity load/unload events on the main thread.
        drainEvents();

        STATS.tick(tickCount);
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
