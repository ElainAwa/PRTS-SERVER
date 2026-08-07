package io.izzel.arclight.common.optimization.general.servercore;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * PRTS region parallelism entity-manager index lock (P3 v05, AI-created).
 *
 * <p>Global read-write lock guarding the vanilla entity-management index
 * structures shared across region workers and the main thread:
 * {@code EntityLookup} (byId/byUuid), {@code EntitySectionStorage}
 * (sections/sectionIds) and {@code EntityTickList}. Every guarded call site
 * acquires the lock for a single map/set operation only (never nested), so no
 * lock ordering issue can arise. The per-{@code EntitySection} element lock
 * lives on the section instance itself (mixin-injected field), keeping the
 * hot entity-move path free of global contention.</p>
 */
public final class EntityLockManager {

    public static final ReentrantReadWriteLock INDEX_LOCK = new ReentrantReadWriteLock();

    private EntityLockManager() {
    }
}
