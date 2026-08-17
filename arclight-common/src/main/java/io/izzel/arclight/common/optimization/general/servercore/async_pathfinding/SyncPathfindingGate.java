/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.async_pathfinding;

/**
 * One-shot pathfinding callers (e.g. {@code AcquirePoi.findPathToPois} via
 * {@code PathNavigation.createPath(Set, int)}) need the synchronous
 * {@code Path} return value. The async pathfinding hook returns null on a
 * successful submission, which made villager job-site claiming loop forever:
 * the brain behavior never reached {@code PoiManager.take}.
 *
 * <p>This gate is marked around the multi-target createPath overload and
 * consumed by the next {@code PathFinder.findPath} on the same thread. It is
 * intentionally consume-once instead of enter/exit: if the caller bails out
 * before invoking the pathfinder (e.g. empty target set), the stale mark only
 * forces one extra synchronous path on that thread and can never leak the
 * whole thread into a permanent state.
 */
public final class SyncPathfindingGate {

    private static final ThreadLocal<Boolean> MARKED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SyncPathfindingGate() {
    }

    /** Marks the next pathfind on this thread as synchronous. */
    public static void mark() {
        MARKED.set(Boolean.TRUE);
    }

    /** Returns true (and clears) if the current pathfind must run synchronously. */
    public static boolean consume() {
        boolean marked = MARKED.get();
        MARKED.set(Boolean.FALSE);
        return marked;
    }
}
