/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.lightengine;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Light-engine telemetry: records per-drain propagation stats into {@link AsyncTaskStats}
 * (prefix {@code [light-engine]}) so the light budget threshold can be tuned from real server
 * data. Written from the light thread (thread-safe LongAdder / AtomicLong), summarized from the
 * main thread via {@link #tick(long)}.
 */
public final class LightEngineStats {

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[light-engine]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("updates")
            .gauge("queue")
            .timer("run")
            .build();

    private LightEngineStats() {
    }

    /** Called from the light thread once per {@code runLightUpdates} drain. */
    public static void record(long runNanos, int updates, int pendingNodes) {
        STATS.record("run", runNanos);
        STATS.add("updates", updates);
        STATS.setGauge("queue", pendingNodes);
    }

    /** Called from the main thread once per server tick to drive the periodic summary log. */
    public static void tick(long serverTick) {
        STATS.tick(serverTick);
    }
}