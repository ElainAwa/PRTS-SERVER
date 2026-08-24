/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.eventbridge;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Event short-circuit telemetry (plan 2026-08-17 A-10), prefix {@code [event-shortcircuit]}.
 * Written from the EntityTickEvent / NeighborNotifyEvent short-circuit mixins (main
 * thread + region workers — LongAdder counters are thread-safe), summarized via
 * {@link #tick(long)}.
 */
public final class EventShortcircuitStats {

    /** Master telemetry switch, driven by {@code event-shortcircuit.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[event-shortcircuit]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("skippedPre")         // EntityTickEvent.Pre not fired (no listeners)
            .counter("skippedPost")        // EntityTickEvent.Post not fired (no listeners)
            .counter("forwardedPre")       // EntityTickEvent.Pre fired (listeners present)
            .counter("forwardedPost")      // EntityTickEvent.Post fired (listeners present)
            .counter("neighborNotifySkipped") // NeighborNotifyEvent not fired (no listeners)
            .counter("skippedBlockForm")      // BlockFormEvent/EntityBlockFormEvent skipped (no listeners)
            .counter("forwardedBlockForm")    // BlockFormEvent/EntityBlockFormEvent fired (listeners present)
            .counter("spawnPositionSkipped")  // MobSpawnEvent.PositionCheck skipped, vanilla rules inlined
            .counter("spawnPositionForwarded") // PositionCheck fired (listeners present)
            .counter("despawnSkipped")        // MobDespawnEvent skipped, vanilla despawn continues
            .counter("despawnForwarded")      // MobDespawnEvent fired (listeners present)
            .build();

    private EventShortcircuitStats() {
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
