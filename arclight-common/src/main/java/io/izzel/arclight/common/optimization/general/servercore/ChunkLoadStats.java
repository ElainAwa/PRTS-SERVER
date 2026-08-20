/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-only instrumentation for the chunk-load pipeline (S2.75 P0). Records where
 * time and volume go across demand queueing, main-thread install, worldgen submit,
 * and async region I/O, without changing any threading or lifecycle behaviour.
 */
public final class ChunkLoadStats {

    private static final LongAdder DEMAND_SUBMITTED = new LongAdder();
    private static final LongAdder DEMAND_DEDUPED = new LongAdder();
    private static final LongAdder DEMAND_DROPPED = new LongAdder();
    private static final LongAdder DEMAND_POLLED = new LongAdder();
    private static final LongAdder FULL_COMPLETED = new LongAdder();
    private static final LongAdder WAIT_TIMEOUT = new LongAdder();
    private static final LongAdder GENERATION_SUBMITTED = new LongAdder();

    private static final Timer WAIT = new Timer();
    private static final Timer INSTALL = new Timer();
    private static final Timer GENERATION_SUBMIT = new Timer();
    private static final Timer REGION_READ = new Timer();

    private static final AtomicLong LAST_INSTALL_COUNT = new AtomicLong();
    private static final AtomicLong LAST_INSTALL_NANOS = new AtomicLong();

    private ChunkLoadStats() {
    }

    public static void demandSubmitted() {
        DEMAND_SUBMITTED.increment();
    }

    public static void demandDeduped() {
        DEMAND_DEDUPED.increment();
    }

    public static void demandDropped() {
        DEMAND_DROPPED.increment();
    }

    public static void demandPolled() {
        DEMAND_POLLED.increment();
    }

    public static void fullCompleted() {
        FULL_COMPLETED.increment();
    }

    public static void waitTimeout() {
        WAIT_TIMEOUT.increment();
    }

    /** One worldgen task submission with its scheduling duration, counted regardless of the budget gate. */
    public static void generationSubmitted(long durationNanos) {
        GENERATION_SUBMITTED.increment();
        GENERATION_SUBMIT.record(durationNanos);
    }

    public static void waitCompleted(long durationNanos) {
        WAIT.record(durationNanos);
    }

    public static void regionRead(long durationNanos) {
        REGION_READ.record(durationNanos);
    }

    /** Records a full main-thread drain pass: how many chunks installed and total time. */
    public static void installPass(int installed, long durationNanos) {
        LAST_INSTALL_COUNT.set(installed);
        LAST_INSTALL_NANOS.set(durationNanos);
        if (installed > 0) {
            INSTALL.record(durationNanos);
        }
    }

    public static String statusText() {
        return "submitted=" + DEMAND_SUBMITTED.sum()
                + " deduped=" + DEMAND_DEDUPED.sum()
                + " dropped=" + DEMAND_DROPPED.sum()
                + " polled=" + DEMAND_POLLED.sum()
                + " completed=" + FULL_COMPLETED.sum()
                + " genSubmitted=" + GENERATION_SUBMITTED.sum()
                + " waitTimeout=" + WAIT_TIMEOUT.sum()
                + " waitMs=" + WAIT.summary()
                + " installMs=" + INSTALL.summary()
                + " genSubmitMs=" + GENERATION_SUBMIT.summary()
                + " regionReadMs=" + REGION_READ.summary()
                + " lastInstall=" + LAST_INSTALL_COUNT.get()
                + "/" + fmt(LAST_INSTALL_NANOS.get() / 1_000_000.0) + "ms";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** Lock-free avg/max over recorded durations (nanoseconds in, ms out). */
    private static final class Timer {
        private final LongAdder total = new LongAdder();
        private final LongAdder count = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        void record(long nanos) {
            if (nanos < 0) {
                nanos = 0;
            }
            total.add(nanos);
            count.increment();
            long cur;
            do {
                cur = max.get();
                if (nanos <= cur || max.compareAndSet(cur, nanos)) {
                    break;
                }
            } while (true);
        }

        String summary() {
            long c = count.sum();
            double avg = c == 0 ? 0.0 : total.sum() / 1_000_000.0 / c;
            return "avg=" + fmt(avg) + " max=" + fmt(max.get() / 1_000_000.0);
        }
    }
}
