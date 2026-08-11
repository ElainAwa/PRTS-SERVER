/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Defers cross-dimension transfers while parallel dimension ticks run: a
 * {@code changeDimension} from a worker would mutate the target dimension's entity
 * list concurrently, so it is queued ({@link DimensionTickManager#enqueueTransfer})
 * and executed on the post-barrier main thread, returning the entity as-is. Targets
 * both {@link Entity} and its override {@link ServerPlayer}.
 */
@Mixin(value = {Entity.class, ServerPlayer.class})
public abstract class EntityMixin_DimTransfer {

    @Inject(method = "changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;",
        at = @At("HEAD"), cancellable = true)
    private void arclight$deferTransfer(DimensionTransition transition, CallbackInfoReturnable<Entity> cir) {
        if (!PRTSFeaturesConfig.parallelDimension) return;
        if (!DimensionTickManager.inDimensionTick()) return;
        Entity self = (Entity) (Object) this;
        if (transition.newLevel() == self.level()) return;
        DimensionTickManager.enqueueTransfer(self, transition);
        cir.setReturnValue(self);
    }
}
