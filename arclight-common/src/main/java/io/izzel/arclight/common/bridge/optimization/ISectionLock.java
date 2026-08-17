/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Exposes the per-section read/write lock injected by
 * {@code EntitySectionMixin_RegionLock} so the spatial index mixin can share the same lock
 * (index writes must be serialized together with the underlying storage writes).
 */
public interface ISectionLock {

    ReentrantReadWriteLock arclight$getSectionLock();
}
