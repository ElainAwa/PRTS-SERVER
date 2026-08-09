/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PRTS region parallelism drop-merge guard (P3 slice 2 fix, AI-created).
 *
 * <p>On a region worker, {@code ItemEntity.mergeWithNeighbours} may merge an
 * item that just fell out of the world bottom (y &lt; minY) and discard it;
 * the removal path ({@code PersistentEntitySectionManager.stopTracking})
 * computes a negative section index in that state and crashes with
 * ArrayIndexOutOfBounds. Merging is a pure optimization (fewer entities), so
 * skipping it on region workers is side-effect-free and avoids the whole
 * removal-path edge case; the main-thread/dimension-worker path is untouched.</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin_RegionMerge {

    @Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
    private void arclight$regionSkipMerge(CallbackInfo ci) {
        if (RegionTickManager.inRegionTick()) {
            ci.cancel();
        }
    }
}
