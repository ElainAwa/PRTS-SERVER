/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * NeoForge EventBus dispatch telemetry.
 *
 * <p>{@code net.neoforged.bus.EventBus} itself is loaded by the bootstrap layer
 * before any arclight mixin configuration is selected, so a Mixin target on
 * {@code EventBus.post} never applies. Instead the NeoForge platform module
 * registers two catch-all listeners on the {@code Event} base class of
 * {@code NeoForge.EVENT_BUS}: the start listener at HIGHEST priority and the
 * end listener at LOWEST priority. {@link #onDispatchStart} /
 * {@link #onDispatchEnd} are the callback pair wired by
 * {@code EventBusTelemetry}.</p>
 *
 * <p>Read-only measurement: it never cancels, reorders or observes event
 * payloads.</p>
 */
public final class EventBusStats {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");
    private static final int LOG_INTERVAL_TICKS = 600;
    /** Phase-specific HIGHEST posts have no matching LOWEST callback; prune them here. */
    private static final long STALE_NANOS = 30_000_000_000L;

    private static final ThreadLocal<ArrayDeque<Entry>> START = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ConcurrentHashMap<String, LongAdder> NANOS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> COUNT = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final LongAdder TOTAL_COUNT = new LongAdder();
    private static final LongAdder START_CALLS = new LongAdder();
    private static final LongAdder END_CALLS = new LongAdder();
    private static volatile long lastLogTick = Long.MIN_VALUE;
    private static volatile java.util.function.IntConsumer refreshCallback;
    private static volatile int lastRefreshTick = Integer.MIN_VALUE;

    private EventBusStats() {
    }

    /** Called by the HIGHEST catch-all listener when event dispatch reaches it. */
    public static void onDispatchStart(Object event) {
        START_CALLS.increment();
        ArrayDeque<Entry> stack = START.get();
        // Keep the stack shallow: stale phase-specific starts cannot be matched
        // by a later LOWEST callback, so drop them opportunistically.
        if ((stack.size() & 0x0F) == 0 && !stack.isEmpty()) {
            long now = System.nanoTime();
            stack.removeIf(entry -> now - entry.startNanos > STALE_NANOS);
        }
        stack.push(new Entry(System.nanoTime(), event));
    }

    /** Called by the LOWEST catch-all listener after all lower-priority handlers. */
    public static void onDispatchEnd(Object event) {
        END_CALLS.increment();
        ArrayDeque<Entry> stack = START.get();
        while (!stack.isEmpty()) {
            Entry top = stack.peek();
            if (top.event == event) {
                stack.pop();
                record(top.startNanos, event.getClass().getName());
                return;
            }
            // A HIGHEST-priority phase post pushes without a matching LOWEST
            // callback. Drop only clearly stale frames so one broken frame can
            // never poison every following measurement.
            if (System.nanoTime() - top.startNanos > STALE_NANOS) {
                stack.pop();
                continue;
            }
            // Unmatched live frame: a phase-specific post invoked our LOWEST
            // listener out of band. Do not guess; leave the stack intact.
            return;
        }
    }

    private static void record(long startNanos, String key) {
        long cost = System.nanoTime() - startNanos;
        NANOS.computeIfAbsent(key, k -> new LongAdder()).add(cost);
        COUNT.computeIfAbsent(key, k -> new LongAdder()).increment();
        TOTAL_NANOS.add(cost);
        TOTAL_COUNT.increment();
    }

    /** Platform layer (NeoForge) installs its per-tick event-class scanner here. */
    public static void setEventBusRefreshCallback(java.util.function.IntConsumer callback) {
        refreshCallback = callback;
    }

    /** Called once per server tick from the PRTS tick path (cheap, non-blocking). */
    public static void tickIfNeeded(int serverTick) {
        java.util.function.IntConsumer callback = refreshCallback;
        if (callback != null && serverTick != lastRefreshTick) {
            lastRefreshTick = serverTick;
            try {
                callback.accept(serverTick);
            } catch (Throwable t) {
                LOGGER.debug("[eventbus] refresh callback failed", t);
            }
        }
        if (serverTick - lastLogTick < LOG_INTERVAL_TICKS) {
            return;
        }
        lastLogTick = serverTick;
        LOGGER.info("[eventbus] {} events total={}ms starts={} ends={} top: {}", TOTAL_COUNT.sum(),
                TOTAL_NANOS.sum() / 1_000_000L, START_CALLS.sum(), END_CALLS.sum(), statusText());
    }

    /** One-line top-5 summary for /servercore status. */
    public static String statusText() {
        List<Map.Entry<String, LongAdder>> all = new ArrayList<>(NANOS.entrySet());
        all.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        StringBuilder sb = new StringBuilder();
        sb.append("total=").append(TOTAL_COUNT.sum()).append(" totalMs=").append(TOTAL_NANOS.sum() / 1_000_000L)
                .append(" starts=").append(START_CALLS.sum()).append(" ends=").append(END_CALLS.sum());
        int shown = 0;
        for (Map.Entry<String, LongAdder> e : all) {
            if (shown >= 5) {
                break;
            }
            long nanos = e.getValue().sum();
            long count = COUNT.getOrDefault(e.getKey(), new LongAdder()).sum();
            sb.append(", ").append(e.getKey())
                    .append(" ").append(nanos / 1_000_000L).append("ms/").append(count);
            shown++;
        }
        return sb.toString();
    }

    private record Entry(long startNanos, Object event) {
    }
}
