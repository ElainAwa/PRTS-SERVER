/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.bridge.optimization;

import net.minecraft.world.level.entity.EntitySection;

/**
 * Volatile back-reference from an entity to the section whose spatial index holds it,
 * maintained by {@code EntitySectionMixin_SpatialIndex} on add/remove so
 * {@code EntityMixin_SectionIndexRebome} can find the index on intra-section movement
 * without a level lookup.
 */
public interface IEntitySectionHolder {

    EntitySection prts$getIndexedSection();

    void prts$setIndexedSection(EntitySection section);
}
