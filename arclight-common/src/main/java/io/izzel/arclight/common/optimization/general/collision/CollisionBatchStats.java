/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.collision;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Collision batch telemetry (audit doc §阶段5·5.2), prefix {@code [collision-batch]}.
 * Written from the collide hot path (thread-safe counters), summarized from the main thread
 * via {@link #tick(long)}.
 */
public final class CollisionBatchStats {

    /** Master telemetry switch, driven by {@code collision-batch.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[collision-batch]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("reusedShapes")        // requested region contained in cached region
            .counter("incrementalFetches")  // step-up delta cap fetched, cache extended
            .counter("fullFetches")         // cache miss / refresh -> vanilla collection
            .build();

    private CollisionBatchStats() {
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static void increment(String name) {
        if (!ENABLED) {
            return;
        }
        STATS.increment(name);
    }

    /** Called from the main thread once per server tick. */
    public static void tick(long serverTick) {
        STATS.tick(serverTick);
    }
}
