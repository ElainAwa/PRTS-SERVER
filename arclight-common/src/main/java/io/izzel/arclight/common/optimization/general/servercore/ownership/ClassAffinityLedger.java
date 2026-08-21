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
    /** Whether MAIN_ONLY_READ feeds the auto-route window (worker reads return null anyway). */
    private static volatile boolean routeOnRead = true;

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ClassAffinityLedger() {
    }

    /** Applies configuration from prts-features.yml; called once during startup. */
    public static void applyConfig(int threshold, long windowTicks, boolean routeOnReadFlag) {
        routeThreshold = Math.max(0, threshold);
        routeWindowTicks = Math.max(20, windowTicks);
        routeOnRead = routeOnReadFlag;
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
                // worker Level.getBlockEntity 恒返回 null（vanilla 语义），读为 null-安全降级。
                // routeOnRead=false 时不喂 auto-route 窗口，避免因纯只读把实体类误路由主线程。
                if (routeOnRead) {
                    entry.recordViolation(tick);
                }
            }
            case MAIN_ONLY_WRITE -> {
                entry.mainOnlyWrites++;
                entry.recordViolation(tick);
            }
            case CROSS_READ -> entry.crossReads++;
            case JOURNAL_WRITE -> entry.journalWrites++;
        }
        if (entry.attributable && !entry.routed.get() && routeThreshold > 0 && entry.violationsInWindow(tick) >= routeThreshold) {
            synchronized (entry) {
                if (entry.routed.compareAndSet(false, true)) {  // S3: atomic CAS
                    entry.learnedTick = tick;  // S3: record when auto-learned
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
        return entry.routed.get() || entry.violationsInWindow(tick) >= routeThreshold;
    }

    /** Number of classes currently routed to the main thread. */
    public static int routedCount() {
        return routedCount(null);
    }

    /** Number of routed classes whose key starts with the given prefix (null = all). */
    public static int routedCount(String prefix) {
        int n = 0;
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            if (e.getValue().routed.get() && (prefix == null || e.getKey().startsWith(prefix))) {
                n++;
            }
        }
        return n;
    }

    /** Sorted names of attributable entity classes currently routed to the main thread. */
    public static List<String> routedClassNames() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            if (e.getValue().routed.get() && e.getValue().attributable
                    && !e.getKey().startsWith("block-entity:")) {
                names.add(e.getKey());
            }
        }
        names.sort(String::compareTo);
        return names;
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
            if (e.getValue().routed.get()) {
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

    /** S3: Snapshot all auto-learned routes (routed=true && learnedTick>0, exclude manual force). */
    public static List<LearnedRoute> snapshotLearnedRoutes() {
        List<LearnedRoute> routes = new ArrayList<>();
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            Entry entry = e.getValue();
            if (entry.routed.get() && entry.learnedTick > 0 && entry.attributable) {
                routes.add(new LearnedRoute(
                    e.getKey(),
                    entry.mainOnlyReads + entry.mainOnlyWrites,
                    entry.learnedTick
                ));
            }
        }
        return routes;
    }

    /** S3: Restore a learned route from persistence (called on server start). */
    public static void restoreLearnedRoute(String className, long learnedTick) {
        Entry entry = ENTRIES.computeIfAbsent(className, k -> new Entry());
        entry.routed.set(true);
        entry.learnedTick = learnedTick;
        entry.attributable = true;  // Assume learned routes are attributable
    }

    /** S3: Data class for JSON serialization of learned routes. */
    public static record LearnedRoute(String className, int violations, long learnedTick) {
    }

    private static final class Entry {
        int mainOnlyReads;
        int mainOnlyWrites;
        int crossReads;
        int journalWrites;

        volatile boolean attributable;
        final java.util.concurrent.atomic.AtomicBoolean routed = new java.util.concurrent.atomic.AtomicBoolean(false);  // S3: concurrent-safe
        volatile long learnedTick = -1L;  // S3: tick when routed was first set (-1 = manual force/not learned)
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
