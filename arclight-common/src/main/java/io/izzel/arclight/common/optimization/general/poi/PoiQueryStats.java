/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.poi;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * POI query fast-path telemetry (audit doc §阶段5·5.1), prefix {@code [poi-query]}.
 * Written from query/load paths (thread-safe counters), summarized from the main thread
 * via {@link #tick(long)}.
 */
public final class PoiQueryStats {

    /** Master telemetry switch, driven by {@code poi-query.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[poi-query]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("indexedChunks")       // fast-path chunks with ≥1 present POI section
            .counter("skippedEmptyChunks")  // fast-path chunks skipped (known, no POI)
            .counter("vanillaChunks")       // never-read chunks -> vanilla getOrLoad path
            .build();

    private PoiQueryStats() {
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
