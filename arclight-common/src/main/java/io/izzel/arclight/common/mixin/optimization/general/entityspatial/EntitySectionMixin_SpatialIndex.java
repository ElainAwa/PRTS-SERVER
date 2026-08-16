/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.entityspatial;

import io.izzel.arclight.common.bridge.optimization.IEntitySectionHolder;
import io.izzel.arclight.common.bridge.optimization.IEntitySpatialIndex;
import io.izzel.arclight.common.bridge.optimization.ISectionLock;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.entityspatial.EntitySpatialIndex;
import io.izzel.arclight.common.optimization.general.entityspatial.EntitySpatialIndexStats;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lazily-built 4x4x4 sub-grid spatial index for pure AABB queries on {@code EntitySection}
 * (audit doc §1.3 / §三.B P2). Typed queries ({@code getEntities(EntityTypeTest, ...)}) are
 * intentionally untouched (already optimized by {@code ClassInstanceMultiMap}'s lazy byClass map
 * and the mod-compat path most depended upon).
 *
 * <p>Compatibility guarantees:
 * <ul>
 *   <li><b>Order</b>: indexed output is sorted by insertion sequence, identical to the vanilla
 *       per-section order (see {@link EntitySpatialIndex}).</li>
 *   <li><b>No misses</b>: query cells are computed from the AABB inflated by 8 blocks, so any
 *       entity whose bounding box intersects the query is scanned (extra candidates are filtered
 *       by the exact intersects test).</li>
 *   <li><b>Lazy</b>: only sections with {@code lighting.min-section-size}+ entities build an
 *       index; smaller sections keep the vanilla linear scan (zero cost).</li>
 *   <li><b>Locking</b>: index writes share the section lock injected by
 *       {@code EntitySectionMixin_RegionLock} (via {@link io.izzel.arclight.common.bridge.optimization.ISectionLock}).</li>
 * </ul>
 */
@Mixin(EntitySection.class)
@LoadIfMod(modid = {ModIds.LITHIUM, ModIds.CANARY, ModIds.RADIUM, ModIds.RECRUITS}, condition = LoadIfMod.ModCondition.ABSENT)
public abstract class EntitySectionMixin_SpatialIndex implements IEntitySpatialIndex {

    @Unique
    private EntitySpatialIndex<EntityAccess> prts$spatialIndex;

    @Shadow @Final
    private ClassInstanceMultiMap<EntityAccess> storage;

    // ---- query: pure AABB queries go through the index when present ----

    @Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)Lnet/minecraft/util/AbortableIterationConsumer$Continuation;",
            at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void prts$indexedQuery(AABB bounds, AbortableIterationConsumer consumer,
                                   CallbackInfoReturnable<AbortableIterationConsumer.Continuation> cir) {
        if (!PRTSFeaturesConfig.entitySpatialIndexEnabled) {
            return;
        }
        EntitySpatialIndex<EntityAccess> index = this.prts$spatialIndex;
        if (index == null) {
            EntitySpatialIndexStats.increment("fallbackQueries");
            EntitySpatialIndexStats.add("fullScanned", ((EntitySection) (Object) this).size());
            return;
        }
        ((ISectionLock) this).arclight$getSectionLock().readLock().lock();
        try {
            cir.setReturnValue(index.query(bounds, consumer));
            EntitySpatialIndexStats.increment("indexedQueries");
        } finally {
            ((ISectionLock) this).arclight$getSectionLock().readLock().unlock();
        }
    }

    // ---- maintenance: add / remove (cross-section moves funnel through both) ----

    @Inject(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V", at = @At("RETURN"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void prts$indexOnAdd(EntityAccess entity, CallbackInfo ci) {
        if (!PRTSFeaturesConfig.entitySpatialIndexEnabled) {
            return;
        }
        if (entity instanceof Entity e && e.level() != null && e.level().isClientSide) {
            return;
        }
        EntitySpatialIndex<EntityAccess> index = this.prts$spatialIndex;
        if (index == null) {
            // lazy build: only index sections dense enough to be worth it; backfill existing entities
            if (((EntitySection) (Object) this).size() < PRTSFeaturesConfig.entitySpatialIndexMinSectionSize) {
                return;
            }
            index = new EntitySpatialIndex<>();
            this.prts$spatialIndex = index;
            EntitySpatialIndexStats.increment("indexesBuilt");
            ((ISectionLock) this).arclight$getSectionLock().writeLock().lock();
            try {
                for (Object e : this.storage) {
                    index.add((EntityAccess) e);
                }
            } finally {
                ((ISectionLock) this).arclight$getSectionLock().writeLock().unlock();
            }
        } else {
            ((ISectionLock) this).arclight$getSectionLock().writeLock().lock();
            try {
                index.add(entity);
            } finally {
                ((ISectionLock) this).arclight$getSectionLock().writeLock().unlock();
            }
        }
        if (entity instanceof IEntitySectionHolder holder) {
            holder.prts$setIndexedSection((EntitySection) (Object) this);
        }
    }

    @Inject(method = "remove(Lnet/minecraft/world/level/entity/EntityAccess;)Z", at = @At("RETURN"))
    @SuppressWarnings("rawtypes")
    private void prts$indexOnRemove(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (!PRTSFeaturesConfig.entitySpatialIndexEnabled) {
            return;
        }
        EntitySpatialIndex<EntityAccess> index = this.prts$spatialIndex;
        if (index == null || !cir.getReturnValue()) {
            return;
        }
        ((ISectionLock) this).arclight$getSectionLock().writeLock().lock();
        try {
            index.remove(entity);
        } finally {
            ((ISectionLock) this).arclight$getSectionLock().writeLock().unlock();
        }
        if (entity instanceof IEntitySectionHolder holder) {
            holder.prts$setIndexedSection(null);
        }
    }

    // ---- re-home on intra-section movement (called from Entity.setBoundingBox) ----

    @Override
    public void prts$indexRebome(EntityAccess entity) {
        if (!PRTSFeaturesConfig.entitySpatialIndexEnabled) {
            return;
        }
        EntitySpatialIndex<EntityAccess> index = this.prts$spatialIndex;
        if (index == null) {
            return;
        }
        ((ISectionLock) this).arclight$getSectionLock().writeLock().lock();
        try {
            index.rebome(entity);
        } finally {
            ((ISectionLock) this).arclight$getSectionLock().writeLock().unlock();
        }
    }
}
