/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.ownership;

/**
 * Enforcement mode for the worker world-access guard.
 *
 * <p>{@code OFF} keeps default behavior with no instrumentation cost beyond a
 * volatile read. {@code STATS} records violations and logs them with rate
 * limiting. {@code ENFORCE} additionally throws {@link AccessViolation} on
 * region workers; the entity-tick wrapper swallows it so the server survives,
 * which makes this mode useful only for pinpointing a single misbehaving mod
 * on a test server.
 */
public enum ThreadPolicy {

    OFF,
    STATS,
    ENFORCE;

    public static ThreadPolicy parse(String value) {
        if (value == null) {
            return STATS;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STATS;
        }
    }
}
