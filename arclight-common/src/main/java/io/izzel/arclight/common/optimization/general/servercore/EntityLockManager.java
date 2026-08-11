/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Global read-write lock guarding the vanilla entity-management index structures
 * shared across region workers and the main thread: {@code EntityLookup},
 * {@code EntitySectionStorage} and {@code EntityTickList}. The per-{@code EntitySection}
 * element lock lives on the section instance itself (mixin-injected field).
 */
public final class EntityLockManager {

    public static final ReentrantReadWriteLock INDEX_LOCK = new ReentrantReadWriteLock();

    private EntityLockManager() {
    }
}
