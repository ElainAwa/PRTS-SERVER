/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.menubroadcast;

import io.izzel.arclight.common.optimization.general.AsyncTaskStats;
import org.apache.logging.log4j.LogManager;

/**
 * Container-menu broadcast telemetry, prefix {@code [menu-broadcast]}.
 * Written from {@code AbstractContainerMenu.broadcastChanges} on the main thread (thread-safe
 * counters), summarized via {@link #tick(long)}.
 */
public final class MenuBroadcastStats {

    /** Master telemetry switch, driven by {@code menu-broadcast.telemetry-enabled}. */
    public static volatile boolean ENABLED = true;

    private static final AsyncTaskStats STATS = AsyncTaskStats.builder("[menu-broadcast]")
            .intervalTicks(600)
            .logger(LogManager.getLogger("PRTS-Optimization"))
            .counter("skippedBroadcasts")   // precheck all-equal -> whole vanilla loop skipped
            .counter("fullBroadcasts")      // a slot/data/carried changed -> vanilla path
            .counter("cooldownHits")        // precheck skipped while in change cooldown
            .counter("slotsChecked")        // slots scanned by the precheck (work avoided in vanilla)
            .build();

    private MenuBroadcastStats() {
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

    public static void addSlots(int count) {
        if (!ENABLED) {
            return;
        }
        STATS.add("slotsChecked", count);
    }

    /** Called from the main thread once per server tick. */
    public static void tick(long serverTick) {
        STATS.tick(serverTick);
    }
}
