/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ServerLevel;

import java.util.function.BooleanSupplier;

/**
 * A parallel tick unit: one independently tickable world partition — a whole
 * dimension or a region of the overworld. Units run on worker threads behind a
 * per-tick barrier on the main thread.
 */
public interface ParallelTickUnit {

    /** Stable identifier used in logs and stats (e.g. "dim:overworld", "region:0"). */
    String name();

    /** The owning dimension level (pre/post events, time sync, tick times). */
    ServerLevel level();

    /** The unit's tick body, executed on a worker thread. */
    void tick(BooleanSupplier hasTimeLeft);

    /** Collect cross-unit updates at the tick boundary (region units). */
    default void collectUpdates() {
    }

    /** Apply cross-unit updates before the next tick (region units). */
    default void applyUpdates() {
    }
}
