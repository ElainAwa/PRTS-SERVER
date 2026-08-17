/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.entityspatial;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Entity spatial-index telemetry: written from the section lock holders / query path
 * (thread-safe LongAdder / AtomicLong), summarized from the main thread via
 * {@link #tick(long)} (prefix {@code [entity-spatial-index]}).
 */
public final class EntitySpatialIndexStats {

    /** Master telemetry switch, driven by {@code entity-spatial-index.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[entity-spatial-index]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("indexedQueries")
            .counter("fallbackQueries")
            .counter("typedQueries")
            .counter("typedFallback")
            .counter("typedScanned")
            .counter("typedSkipped")
            .counter("membersSkipped")
            .counter("vanillaOrderQueries")
            .counter("indexesBuilt")
            .counter("candidatesScanned")
            .counter("fullScanned")
            .gauge("indexedEntities")
            .build();

    private EntitySpatialIndexStats() {
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

    public static void add(String name, long delta) {
        if (!ENABLED) {
            return;
        }
        STATS.add(name, delta);
    }

    public static void setGauge(String name, long value) {
        if (!ENABLED) {
            return;
        }
        STATS.setGauge(name, value);
    }

    /** Called from the main thread once per server tick. */
    public static void tick(long serverTick) {
        STATS.tick(serverTick);
    }
}
