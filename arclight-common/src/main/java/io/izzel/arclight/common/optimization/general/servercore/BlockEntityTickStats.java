/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-block-entity tick cost ledger. Every main-thread BE tick records its wall
 * time under the BE type key (registry id, e.g. minecraft:furnace), so the
 * block-entity dependency-scheduling work can start from data instead of guesses.
 */
public final class BlockEntityTickStats {

    private static final ConcurrentHashMap<String, TypeStats> TYPES = new ConcurrentHashMap<>();

    private BlockEntityTickStats() {
    }

    /**
     * Records one BE tick's wall time. Returns true when this tick set a new max,
     * signalling the caller to follow up with {@link #recordMaxPos} — so the hot path
     * never evaluates the (megamorphic) getPos() unless a new peak was actually hit.
     */
    public static boolean record(String typeKey, long nanos) {
        TypeStats stats = TYPES.computeIfAbsent(typeKey, ignored -> new TypeStats());
        stats.nanos.add(nanos);
        stats.ticks.increment();
        long prev = stats.maxNanos.get();
        while (nanos > prev) {
            if (stats.maxNanos.compareAndSet(prev, nanos)) {
                return true;
            }
            prev = stats.maxNanos.get();
        }
        return false;
    }

    /** Records the position of a BE that just set a new max tick time (cold path only). */
    public static void recordMaxPos(String typeKey, BlockPos pos) {
        TypeStats stats = TYPES.get(typeKey);
        if (stats != null) {
            stats.maxPos.set(String.valueOf(pos));
        }
    }

    /** One-line top-N summary for /servercore status. */
    public static String statusText(int limit) {
        if (TYPES.isEmpty()) {
            return "no block-entity ticks recorded";
        }
        List<Map.Entry<String, TypeStats>> all = new ArrayList<>(TYPES.entrySet());
        all.sort(Comparator.comparingLong((Map.Entry<String, TypeStats> e) -> e.getValue().nanos.sum()).reversed());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, TypeStats> e : all) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            TypeStats s = e.getValue();
            sb.append(e.getKey())
                    .append(" ").append(s.nanos.sum() / 1_000_000L).append("ms/")
                    .append(s.ticks.sum())
                    .append("t max=").append(s.maxNanos.get() / 1_000_000L).append("ms");
            String pos = s.maxPos.get();
            if (pos != null && !pos.isEmpty()) {
                sb.append('@').append(pos);
            }
            shown++;
        }
        return sb.toString();
    }

    private static final class TypeStats {
        final LongAdder nanos = new LongAdder();
        final LongAdder ticks = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicReference<String> maxPos = new AtomicReference<>("");
    }
}
