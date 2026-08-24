/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only attribution telemetry for the main-thread routed entity drain.
 * Answers "which entity classes dominate {@code drainMainThreadEntityTicks}" and why
 * each class is routed, without changing tick order or behaviour.
 *
 * <p>Hot path is zero-contention: the drain loop accumulates into a caller-owned
 * {@link Accumulator} (main thread only) and merges once per pass into the global
 * concurrent map. Sorting happens only when a summary is rendered.</p>
 */
public final class RoutedDrainStats {

    /** Per-class aggregate (global). */
    private static final ConcurrentHashMap<String, ClassAgg> GLOBAL = new ConcurrentHashMap<>();

    /** Last drain pass snapshot (for status/log; written by main thread only). */
    private static volatile long lastEntities;
    private static volatile long lastTotalNanos;
    private static volatile long lastMaxNanos;
    private static volatile int lastQueueDepth;  // queue size before drainTo
    private static volatile int lastProcessed;   // actually processed (may be < queueDepth if batched)

    private RoutedDrainStats() {
    }

    /** Main-thread-owned accumulator for one drain pass; reused across ticks, no locks. */
    public static final class Accumulator {
        private final Map<String, long[]> perClass = new java.util.HashMap<>();
        private long entities;
        private long totalNanos;
        private long maxNanos;
        private int queueDepth;  // set before drain loop starts

        /** Records the queue depth before this drain pass starts. */
        public void setQueueDepth(int depth) {
            this.queueDepth = depth;
        }

        /** Records one entity tick: class label, its routed reason, and its duration. */
        public void record(String className, String reason, long nanos) {
            long[] slot = perClass.get(className);
            if (slot == null) {
                // [0]=count [1]=totalNanos [2]=maxNanos [3]=reasonOrdinal
                slot = new long[]{0L, 0L, 0L, reasonOrdinal(reason)};
                perClass.put(className, slot);
            }
            slot[0]++;
            slot[1] += nanos;
            if (nanos > slot[2]) {
                slot[2] = nanos;
            }
            entities++;
            totalNanos += nanos;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
        }

        /** Merges this pass into the global map and resets for reuse (main thread). */
        public void flush() {
            if (entities == 0) {
                return;
            }
            for (Map.Entry<String, long[]> e : perClass.entrySet()) {
                long[] v = e.getValue();
                ClassAgg agg = GLOBAL.computeIfAbsent(e.getKey(), k -> new ClassAgg(reasonName((int) v[3])));
                agg.count.add(v[0]);
                agg.totalNanos.add(v[1]);
                agg.updateMax(v[2]);
            }
            lastEntities = entities;
            lastTotalNanos = totalNanos;
            lastMaxNanos = maxNanos;
            lastQueueDepth = queueDepth;
            lastProcessed = (int) entities;  // track processed count
            perClass.clear();
            entities = 0;
            totalNanos = 0;
            maxNanos = 0;
            queueDepth = 0;
            maxNanos = 0;
        }
    }

    private static final class ClassAgg {
        final java.util.concurrent.atomic.LongAdder count = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.LongAdder totalNanos = new java.util.concurrent.atomic.LongAdder();
        final java.util.concurrent.atomic.AtomicLong maxNanos = new java.util.concurrent.atomic.AtomicLong();
        final String reason;

        ClassAgg(String reason) {
            this.reason = reason;
        }

        void updateMax(long v) {
            long cur;
            do {
                cur = maxNanos.get();
                if (v <= cur || maxNanos.compareAndSet(cur, v)) {
                    break;
                }
            } while (true);
        }
    }

    private static long reasonOrdinal(String reason) {
        switch (reason) {
            case "force": return 0;
            case "unsafe": return 1;
            case "seed": return 2;
            case "auto-ledger": return 3;
            case "vehicle-passenger": return 4;
            default: return 5;
        }
    }

    private static String reasonName(int ordinal) {
        switch (ordinal) {
            case 0: return "force";
            case 1: return "unsafe";
            case 2: return "seed";
            case 3: return "auto-ledger";
            case 4: return "vehicle-passenger";
            default: return "other";
        }
    }

    /** Compact one-line summary for /servercore status and periodic logging. */
    public static String statusText(int topN) {
        long total = lastTotalNanos;
        StringBuilder sb = new StringBuilder();
        sb.append("lastEntities=").append(lastEntities)
                .append(" lastTotalMs=").append(fmt(total / 1_000_000.0))
                .append(" lastMaxMs=").append(fmt(lastMaxNanos / 1_000_000.0))
                .append(" queueDepth=").append(lastQueueDepth)
                .append(" processed=").append(lastProcessed);  // show batching effect
        List<Map.Entry<String, ClassAgg>> rows = new ArrayList<>(GLOBAL.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().totalNanos.sum(), a.getValue().totalNanos.sum()));
        int shown = 0;
        for (Map.Entry<String, ClassAgg> e : rows) {
            if (shown >= topN) {
                break;
            }
            ClassAgg agg = e.getValue();
            sb.append(" [").append(shortName(e.getKey()))
                    .append(" n=").append(agg.count.sum())
                    .append(" ms=").append(fmt(agg.totalNanos.sum() / 1_000_000.0))
                    .append(" max=").append(fmt(agg.maxNanos.get() / 1_000_000.0))
                    .append(" why=").append(agg.reason)
                    .append("]");
            shown++;
        }
        return sb.toString();
    }

    private static String shortName(String className) {
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
