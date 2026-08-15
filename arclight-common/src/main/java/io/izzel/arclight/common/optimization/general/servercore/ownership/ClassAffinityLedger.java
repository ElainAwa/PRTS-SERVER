/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.ownership;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-entity-class ledger of worker world-access violations.
 *
 * <p>MAIN_ONLY violations feed a sliding window: when a class exceeds the
 * configured threshold inside the window it is marked routed and the region
 * dispatcher moves that class to the main-thread entity queue from the next
 * tick on. Routing is sticky for the server session (no flip-flop), and the
 * window only decides when routing starts.
 *
 * <p>Class names are used as keys instead of Class objects to avoid leaking
 * class loaders when mods are replaced or the class cache is cleared.
 */
public final class ClassAffinityLedger {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ThreadPolicy");

    private static volatile int routeThreshold = 2;
    private static volatile long routeWindowTicks = 2400;

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ClassAffinityLedger() {
    }

    /** Applies configuration from prts-features.yml; called once during startup. */
    public static void applyConfig(int threshold, long windowTicks) {
        routeThreshold = Math.max(0, threshold);
        routeWindowTicks = Math.max(20, windowTicks);
    }

    public static int routeThreshold() {
        return routeThreshold;
    }

    public static long routeWindowTicks() {
        return routeWindowTicks;
    }

    /**
     * Records one access. {@code attributable} is false when the owner is a
     * synthetic fallback (e.g. a worker thread name because no entity was on
     * the tick stack); such entries are still counted for the status view but
     * never become routing decisions.
     */
    public static void record(String className, WorldAccessGuard.AccessKind kind, long tick, boolean attributable) {
        Entry entry = ENTRIES.computeIfAbsent(className, ignored -> new Entry());
        if (attributable) {
            entry.attributable = true;
        }
        switch (kind) {
            case MAIN_ONLY_READ -> {
                entry.mainOnlyReads++;
                entry.recordViolation(tick);
            }
            case MAIN_ONLY_WRITE -> {
                entry.mainOnlyWrites++;
                entry.recordViolation(tick);
            }
            case CROSS_READ -> entry.crossReads++;
            case JOURNAL_WRITE -> entry.journalWrites++;
        }
        if (entry.attributable && !entry.routed && routeThreshold > 0 && entry.violationsInWindow(tick) >= routeThreshold) {
            synchronized (entry) {
                if (!entry.routed) {
                    entry.routed = true;
                    LOGGER.info("[thread-policy] auto-route {} to the main thread after {} window violations (threshold={}, window={} ticks)",
                            className, entry.windowViolations, routeThreshold, routeWindowTicks);
                }
            }
        }
    }

    /**
     * Whether the dispatcher should route this class to the main thread:
     * already marked routed, or currently over the sliding-window threshold.
     */
    public static boolean shouldRouteMainThread(String className, long tick) {
        if (routeThreshold <= 0) {
            return false;
        }
        Entry entry = ENTRIES.get(className);
        if (entry == null) {
            return false;
        }
        return entry.routed || entry.violationsInWindow(tick) >= routeThreshold;
    }

    /** Number of classes currently routed to the main thread. */
    public static int routedCount() {
        int n = 0;
        for (Entry entry : ENTRIES.values()) {
            if (entry.routed) {
                n++;
            }
        }
        return n;
    }

    /** Human-readable top-N summary, one entry per line; routed classes get a * prefix. */
    public static String summary(int limit) {
        List<Map.Entry<String, Entry>> all = new ArrayList<>(ENTRIES.entrySet());
        all.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().total()).reversed());
        if (all.isEmpty()) {
            return "no violations recorded";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Entry> e : all) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            if (e.getValue().routed) {
                sb.append('*');
            }
            sb.append(e.getKey()).append(" R=").append(e.getValue().mainOnlyReads)
                    .append(" W=").append(e.getValue().mainOnlyWrites)
                    .append(" X=").append(e.getValue().crossReads)
                    .append(" J=").append(e.getValue().journalWrites);
            shown++;
        }
        return sb.toString();
    }

    private static final class Entry {
        int mainOnlyReads;
        int mainOnlyWrites;
        int crossReads;
        int journalWrites;

        volatile boolean attributable;
        volatile boolean routed;
        long windowStartTick = -1L;
        int windowViolations;

        void recordViolation(long tick) {
            if (windowStartTick < 0 || tick - windowStartTick >= routeWindowTicks) {
                windowStartTick = tick;
                windowViolations = 0;
            } else if (tick < windowStartTick) {
                // Tick counter reset (rare: /reload never resets it, but guard anyway).
                windowStartTick = tick;
                windowViolations = 0;
            }
            windowViolations++;
        }

        int violationsInWindow(long tick) {
            if (windowStartTick < 0) {
                return 0;
            }
            if (tick - windowStartTick >= routeWindowTicks || tick < windowStartTick) {
                return 0;
            }
            return windowViolations;
        }

        long total() {
            return (long) mainOnlyReads + mainOnlyWrites + crossReads + journalWrites;
        }
    }
}
