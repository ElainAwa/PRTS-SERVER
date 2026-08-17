/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.eventbridge;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Forge bridge telemetry (plan 2026-08-17 A-05), prefix {@code [event-bridge]}.
 * Written from the dispatchers (main + region/dimension workers — LongAdder counters
 * are thread-safe), summarized via {@link #tick(long)}.
 */
public final class EventBridgeStats {

    /** Master telemetry switch, driven by {@code event-bridge.on-demand-registration.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[event-bridge]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("dispatcherRegister")     // gate 0→1: dispatcher put on the Forge bus
            .counter("dispatcherUnregister")   // gate 1→0: dispatcher removed from the bus
            .counter("forwardedEvents")        // bridge fully forwarded (listeners present)
            .counter("skippedEvents")          // P0-2 precheck: no listeners -> construction skipped
            .counter("capturedOnly")           // BlockBreak dispatcher: capture-chain only (no BlockBreakEvent listeners)
            .build();

    private EventBridgeStats() {
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
