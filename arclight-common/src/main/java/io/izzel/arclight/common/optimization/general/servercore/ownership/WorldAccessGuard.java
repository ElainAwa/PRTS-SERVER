/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.ownership;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionLevel;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime guard for worker-thread world access. The boundary mixins call
 * {@link #checkMainOnlyRead}, {@link #checkMainOnlyWrite} and
 * {@link #checkCrossAreaRead}; the guard itself decides whether the current
 * thread is a parallel tick worker and what to do under the configured policy.
 *
 * <p>Production default is {@link ThreadPolicy#STATS}: record and rate-limited
 * logging only, identical world behavior to v1.0.35.
 */
public final class WorldAccessGuard {

    public enum AccessKind {
        /** Worker touched main-thread-only read state, e.g. a live BlockEntity. */
        MAIN_ONLY_READ,
        /** Worker touched main-thread-only write state, e.g. addFreshEntity. */
        MAIN_ONLY_WRITE,
        /** Worker read outside its authoritative region (design-legal, counted). */
        CROSS_READ,
        /** Worker write outside its region was journaled (design-legal, counted). */
        JOURNAL_WRITE
    }

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ThreadPolicy");
    private static final int LOG_PER_MINUTE_DEFAULT = 20;

    private static volatile ThreadPolicy policy = ThreadPolicy.OFF;
    private static volatile long logIntervalNanos = 60_000_000_000L / LOG_PER_MINUTE_DEFAULT;

    private static final LongAdder TOTAL_READS = new LongAdder();
    private static final LongAdder TOTAL_WRITES = new LongAdder();
    private static final ConcurrentHashMap<String, Long> LAST_WARN_NANOS = new ConcurrentHashMap<>();

    private WorldAccessGuard() {
    }

    /** Applies configuration from prts-features.yml; called once during startup. */
    public static void applyConfig(ThreadPolicy threadPolicy, int logPerMinute) {
        policy = threadPolicy == null ? ThreadPolicy.STATS : threadPolicy;
        int perMinute = logPerMinute <= 0 ? LOG_PER_MINUTE_DEFAULT : logPerMinute;
        logIntervalNanos = 60_000_000_000L / perMinute;
        LOGGER.info("world-access guard policy={} logPerMinute={}", policy, perMinute);
    }

    public static boolean enabled() {
        return policy != ThreadPolicy.OFF;
    }

    public static ThreadPolicy policy() {
        return policy;
    }

    public static void checkMainOnlyRead(Level level, BlockPos pos) {
        record(level, pos, AccessKind.MAIN_ONLY_READ);
    }

    public static void checkMainOnlyWrite(Level level, BlockPos pos) {
        record(level, pos, AccessKind.MAIN_ONLY_WRITE);
    }

    /**
     * Entity queries from a worker: a query box spanning outside the worker's
     * region is counted as a cross-region read, never as a routing violation.
     */
    public static void checkCrossAreaRead(Level level, AABB aabb) {
        if (!enabled()) {
            return;
        }
        int region = RegionTickManager.currentRegion();
        if (region < 0 || !isWorkerTick()) {
            return;
        }
        int minRegion = RegionLevel.regionId((int) Math.floor(aabb.minX) >> 4);
        int maxRegion = RegionLevel.regionId((int) Math.floor(aabb.maxX) >> 4);
        if (minRegion != region || maxRegion != region) {
            record(level, BlockPos.containing(aabb.minX, aabb.minY, aabb.minZ), AccessKind.CROSS_READ);
        }
    }

    private static boolean isWorkerTick() {
        return RegionTickManager.isRegionWorker() || DimensionTickManager.isDimensionTickThread();
    }

    private static void record(Level level, BlockPos pos, AccessKind kind) {
        if (!enabled()) {
            return;
        }
        if (!isWorkerTick()) {
            return;
        }
        String owner = RegionTickManager.currentEntityClassName();
        boolean attributable = owner != null && !owner.isEmpty();
        if (!attributable) {
            owner = Thread.currentThread().getName();
        }
        long tick = level.getServer() != null ? level.getServer().getTickCount() : 0L;
        ClassAffinityLedger.record(owner, kind, tick, attributable);
        if (kind == AccessKind.MAIN_ONLY_READ || kind == AccessKind.MAIN_ONLY_WRITE) {
            if (kind == AccessKind.MAIN_ONLY_READ) {
                TOTAL_READS.increment();
            } else {
                TOTAL_WRITES.increment();
            }
            maybeLog(owner, kind, pos);
            if (policy == ThreadPolicy.ENFORCE && RegionTickManager.isRegionWorker()) {
                // Dimension workers run whole ServerLevel ticks and have no per-entity
                // catch wrapper; enforce throws only on region workers, which do.
                throw new AccessViolation(owner, kind, pos);
            }
        }
    }

    private static void maybeLog(String owner, AccessKind kind, BlockPos pos) {
        long now = System.nanoTime();
        Long last = LAST_WARN_NANOS.putIfAbsent(owner, now);
        if (last == null || now - last >= logIntervalNanos) {
            if (last == null || LAST_WARN_NANOS.replace(owner, last, now)) {
                LOGGER.warn("[thread-policy] {} by {} at {} in {} ({} total, {} total)",
                        kind.name().toLowerCase(Locale.ROOT), owner, pos,
                        Thread.currentThread().getName(), TOTAL_READS.sum(), TOTAL_WRITES.sum());
            }
        }
    }

    /** One-line status for /servercore status. */
    public static String statusText() {
        return "policy=" + policy.name().toLowerCase(Locale.ROOT)
                + " violations R=" + TOTAL_READS.sum() + " W=" + TOTAL_WRITES.sum()
                + " autoRouted=" + ClassAffinityLedger.routedCount()
                + " top: " + ClassAffinityLedger.summary(5);
    }
}
