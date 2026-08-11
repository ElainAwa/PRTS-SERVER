/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared async task statistics: counter/group/gauge/timer metrics with a periodic
 * summary log line, reused by the async pathfinding, dimension parallelism and
 * region sync modules.
 *
 * <p>Log format: {@code [module-prefix] counter1=v1 group1[m1=v1 m2=v2] gauge1=v1 timer1=avg=1.2ms max=3.4ms}.
 * Counters are {@link LongAdder}, gauges {@link AtomicLong}; the summary writer is
 * called from a single thread ({@link #tick(long)}).</p>
 */
public final class AsyncTaskStats {

    private String prefix;
    private long intervalTicks;
    private Logger logger;

    private final Map<String, LongAdder> counters = new LinkedHashMap<>();
    private final List<String> counterOrder = new ArrayList<>();
    private final Map<String, List<String>> groups = new LinkedHashMap<>(); // group -> member order
    private final Map<String, AtomicLong> gauges = new LinkedHashMap<>();
    private final List<String> gaugeOrder = new ArrayList<>();
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final List<String> timerOrder = new ArrayList<>();

    private long lastTick = -1;

    private AsyncTaskStats() {
    }

    /** Counter without a group. */
    public AsyncTaskStats counter(String name) {
        counters.put(name, new LongAdder());
        counterOrder.add(name);
        return this;
    }

    /** Group of named counters, rendered as {@code group[m1=v1 m2=v2]}. */
    public AsyncTaskStats group(String group, String... members) {
        List<String> list = new ArrayList<>();
        for (String m : members) {
            counters.put(group + "." + m, new LongAdder());
            list.add(m);
        }
        groups.put(group, list);
        return this;
    }

    /** Instantaneous value rendered as {@code name=v}. */
    public AsyncTaskStats gauge(String name) {
        gauges.put(name, new AtomicLong());
        gaugeOrder.add(name);
        return this;
    }

    /** Duration statistics rendered as {@code name=avg=..ms max=..ms}. */
    public AsyncTaskStats timer(String name) {
        timers.put(name, new Timer());
        timerOrder.add(name);
        return this;
    }

    /** Increment a named counter (member names are {@code group.member}). */
    public void increment(String name) {
        LongAdder c = counters.get(name);
        if (c != null) c.increment();
    }

    /** Add a delta (may be negative) to a named counter. */
    public void add(String name, long delta) {
        LongAdder c = counters.get(name);
        if (c != null) c.add(delta);
    }

    /** Set a gauge value (called from the owning thread, e.g. after each drain). */
    public void setGauge(String name, long value) {
        AtomicLong g = gauges.get(name);
        if (g != null) g.set(value);
    }

    /** Record a duration sample in nanoseconds. */
    public void record(String name, long durationNanos) {
        Timer t = timers.get(name);
        if (t != null) t.record(durationNanos);
    }

    /** Average duration of a timer in milliseconds (0 if absent; read-only, main thread). */
    public double avgMillis(String name) {
        Timer t = timers.get(name);
        return t == null ? 0.0 : t.avgMillis();
    }

    /** Sum of a counter (0 if absent; read-only, main thread). */
    public long counterSum(String name) {
        LongAdder c = counters.get(name);
        return c == null ? 0L : c.sum();
    }

    /** Registers a timer at runtime if absent (startup only, main thread). */
    public synchronized void ensureTimer(String name) {
        if (!timers.containsKey(name)) {
            timers.put(name, new Timer());
            timerOrder.add(name);
        }
    }

    /** Registers a group member at runtime if absent (startup only, main thread). */
    public synchronized void ensureGroupMember(String group, String member) {
        String key = group + "." + member;
        if (!counters.containsKey(key)) {
            counters.put(key, new LongAdder());
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(member);
        }
    }

    /** Periodic summary driver; call once per server tick from the main thread. */
    public void tick(long serverTick) {
        if (lastTick < 0) {
            lastTick = serverTick;
            return;
        }
        if (serverTick - lastTick < intervalTicks) return;
        lastTick = serverTick;
        logSummary();
    }

    private void logSummary() {
        if (!logger.isInfoEnabled()) return;
        StringBuilder sb = new StringBuilder(prefix).append(' ');
        boolean first = true;
        for (String name : counterOrder) {
            if (!groups.containsKey(name)) {
                appendKv(sb, first, name, counters.get(name).sum());
                first = false;
            }
        }
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            StringBuilder g = new StringBuilder(e.getKey()).append('[');
            boolean gf = true;
            for (String m : e.getValue()) {
                if (!gf) g.append(' ');
                g.append(m).append('=').append(counters.get(e.getKey() + "." + m).sum());
                gf = false;
            }
            g.append(']');
            if (!first) sb.append(' ');
            sb.append(g);
            first = false;
        }
        for (String name : gaugeOrder) {
            appendKv(sb, first, name, gauges.get(name).get());
            first = false;
        }
        for (String name : timerOrder) {
            Timer t = timers.get(name);
            if (!first) sb.append(' ');
            sb.append(name).append("=avg=")
                    .append(String.format(Locale.ROOT, "%.1f", t.avgMillis()))
                    .append("ms max=")
                    .append(String.format(Locale.ROOT, "%.1f", t.maxMillis()))
                    .append("ms");
            first = false;
        }
        logger.info(sb.toString());
    }

    private static void appendKv(StringBuilder sb, boolean first, String name, long v) {
        if (!first) sb.append(' ');
        sb.append(name).append('=').append(v);
    }

    public static Builder builder(String prefix) {
        return new Builder(prefix);
    }

    public static final class Builder {
        private final String prefix;
        private long intervalTicks = 600;
        private Logger logger = LogManager.getLogger("Arclight");
        private final AsyncTaskStats stats;

        Builder(String prefix) {
            this.prefix = prefix;
            this.stats = new AsyncTaskStats();
        }

        public Builder intervalTicks(long ticks) {
            this.intervalTicks = ticks;
            return this;
        }

        public Builder logger(Logger logger) {
            this.logger = logger;
            return this;
        }

        public Builder counter(String name) {
            stats.counter(name);
            return this;
        }

        public Builder group(String group, String... members) {
            stats.group(group, members);
            return this;
        }

        public Builder gauge(String name) {
            stats.gauge(name);
            return this;
        }

        public Builder timer(String name) {
            stats.timer(name);
            return this;
        }

        public AsyncTaskStats build() {
            stats.prefix = prefix;
            stats.intervalTicks = intervalTicks;
            stats.logger = logger;
            return stats;
        }
    }

    /** Lock-free average/max over recorded durations. */
    private static final class Timer {
        private final LongAdder total = new LongAdder();
        private final LongAdder count = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        void record(long nanos) {
            total.add(nanos);
            count.increment();
            long cur;
            do {
                cur = max.get();
                if (nanos <= cur || max.compareAndSet(cur, nanos)) break;
            } while (true);
        }

        double avgMillis() {
            long c = count.sum();
            return c == 0 ? 0.0 : total.sum() / 1_000_000.0 / c;
        }

        double maxMillis() {
            return max.get() / 1_000_000.0;
        }
    }
}
