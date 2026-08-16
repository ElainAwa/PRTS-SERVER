/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.lightengine;

/**
 * Per-tick light propagation budget state machine (JDK-only, no Minecraft deps so it can be
 * unit-tested standalone). Consumed by {@code LightEngineMixin_LightBudget} to cap how many
 * positions the vanilla light drain loop processes per server tick; once exhausted the loop is
 * told to defer, leaving the remaining propagation queued for the next tick (final lighting is
 * unchanged, only the rate is spread out).
 */
public final class LightBudget {

    private static long spent = 0L;
    private static long lastTick = -1L;

    private LightBudget() {
    }

    /**
     * Decide whether the propagation drain should advance one more position or defer.
     *
     * @param tick      current server tick (>= 0; caller passes -1 when no server and bypasses us)
     * @param queueEmpty true if the underlying propagation queue is actually empty
     * @param enabled   budget toggle (same source as {@code lighting.budget-enabled})
     * @param perTick   per-tick position budget; <= 0 means unlimited (vanilla behavior)
     * @return true to defer (stop draining this tick, leave the rest queued), false to keep going
     */
    public static boolean shouldDefer(long tick, boolean queueEmpty, boolean enabled, int perTick) {
        if (!enabled || perTick <= 0) {
            return queueEmpty;
        }
        if (tick != lastTick) {
            spent = 0L;
            lastTick = tick;
        }
        if (spent >= perTick) {
            return true; // budget exhausted -> defer, remainder processed next tick
        }
        spent++;
        return queueEmpty;
    }
}