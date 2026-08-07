package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ServerLevel;

import java.util.function.BooleanSupplier;

/**
 * PRTS parallel tick unit (P3 slice 0, AI-created).
 * A unit is one independently tic-able world partition: a whole dimension
 * (P2) or, later, a region of the overworld (P3). Units run on worker
 * threads behind a per-tick barrier on the main thread.
 */
public interface ParallelTickUnit {

    /** Stable identifier used in logs and stats (e.g. "dim:overworld", later "region:0"). */
    String name();

    /** The owning dimension level (pre/post events, time sync, tick times). */
    ServerLevel level();

    /** The unit's tick body, executed on a worker thread. */
    void tick(BooleanSupplier hasTimeLeft);

    /** Collect cross-unit updates at the tick boundary (P3 region units). */
    default void collectUpdates() {
    }

    /** Apply cross-unit updates before the next tick (P3 region units). */
    default void applyUpdates() {
    }
}
