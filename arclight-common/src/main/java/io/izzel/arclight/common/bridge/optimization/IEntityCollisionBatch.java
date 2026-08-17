/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import io.izzel.arclight.common.optimization.general.collision.CollisionBatch;

/**
 * Per-entity collision batch holder (audit doc §阶段5·5.2). Implemented by
 * {@code EntityMixin_CollisionBatch}; the batch lives only for the duration of one
 * {@code Entity.collide} frame and is null outside of it.
 */
public interface IEntityCollisionBatch {

    CollisionBatch arclight$getCollisionBatch();

    void arclight$setCollisionBatch(CollisionBatch batch);
}
