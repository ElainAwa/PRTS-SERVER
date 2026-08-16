/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.entityspatial;

import io.izzel.arclight.common.bridge.optimization.IEntitySectionHolder;
import io.izzel.arclight.common.bridge.optimization.IEntitySpatialIndex;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-homes an entity in its section's spatial index when it moves <em>within</em> the section.
 *
 * <p>{@code Entity.setBoundingBox(AABB)} is the sole runtime writer of the {@code bb} field and
 * fires on every {@code setPos} (after {@code setPosRaw} → {@code onMove}) as well as on
 * bb-only changes (refreshDimensions / pose). Cross-section moves are already handled by
 * {@code EntitySection.add/remove} (via {@code PersistentEntitySectionManager$Callback.onMove});
 * the {@code setBoundingBox} hook only re-homes within the current section. The back-reference to
 * the holding section is maintained by {@code EntitySectionMixin_SpatialIndex} on add/remove.
 *
 * <p>The re-home itself acquires the section write lock (shared with the storage writes), so it
 * is safe against concurrent queries and spawns.
 */
@Mixin(Entity.class)
@LoadIfMod(modid = {ModIds.LITHIUM, ModIds.CANARY, ModIds.RADIUM, ModIds.RECRUITS}, condition = LoadIfMod.ModCondition.ABSENT)
public abstract class EntityMixin_SectionIndexRebome implements IEntitySectionHolder {

    @Unique
    private volatile EntitySection prts$indexedSection;

    @Override
    public EntitySection prts$getIndexedSection() {
        return this.prts$indexedSection;
    }

    @Override
    public void prts$setIndexedSection(EntitySection section) {
        this.prts$indexedSection = section;
    }

    @Inject(method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V", at = @At("TAIL"))
    private void prts$rebomeOnBoundingBoxChange(AABB aabb, CallbackInfo ci) {
        if (!PRTSFeaturesConfig.entitySpatialIndexEnabled) {
            return;
        }
        EntitySection section = this.prts$indexedSection;
        if (section == null) {
            return; // not indexed (section below threshold / feature off) -> zero cost
        }
        if (((Entity) (Object) this).level() == null || ((Entity) (Object) this).level().isClientSide) {
            return;
        }
        if (section instanceof IEntitySpatialIndex index) {
            index.prts$indexRebome((Entity) (Object) this);
        }
    }
}
