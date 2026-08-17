/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import net.minecraft.world.level.entity.EntityAccess;

/**
 * Spatial-index entry points on {@code EntitySection}, used by
 * {@code EntityMixin_SectionIndexRebome} to re-home an entity on intra-section movement.
 * The implementor acquires the section write lock itself.
 */
public interface IEntitySpatialIndex {

    void prts$indexRebome(EntityAccess entity);
}
