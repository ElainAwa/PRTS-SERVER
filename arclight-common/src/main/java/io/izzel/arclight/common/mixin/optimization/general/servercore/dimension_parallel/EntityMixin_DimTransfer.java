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
 * PRTS deferred cross-dimension transfer (P2 experiment, AI-created).
 *
 * <p>While the parallel dimension ticks are running on worker threads
 * ({@link DimensionTickManager#inDimensionTick()}), a cross-dimension
 * {@code changeDimension} would mutate the target dimension's entity list
 * concurrently with that dimension's own tick. Such transfers are deferred to
 * the post-barrier main thread ({@link DimensionTickManager#enqueueTransfer})
 * and the caller receives the entity itself as a placeholder.</p>
 *
 * <p>Both {@link Entity} and its override {@link ServerPlayer} are targeted
 * (ServerPlayer overrides changeDimension). The override typically calls
 * {@code super.changeDimension(...)}; the mixin on ServerPlayer fires first and
 * returns the player as-is, so the Entity-level handler never runs for players.
 * Entities bypassing the override (mods calling the base method directly) are
 * still caught by the Entity-level handler. Deferred transfers are counted per
 * type in the {@code [dimension-tick] pendingTransfer[player/entity/...]} stats,
 * which doubles as the non-player transfer frequency probe for the P2 smoke runs.
 * Same-dimension transitions (e.g. respawn-in-place) are not intercepted.</p>
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
