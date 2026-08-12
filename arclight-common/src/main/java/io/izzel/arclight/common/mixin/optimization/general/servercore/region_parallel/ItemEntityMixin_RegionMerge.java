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
 * Skips item drop-merging on region workers: merging an item that fell out of the
 * world bottom (y &lt; minY) triggers a removal path that computes a negative section
 * index and crashes. Merging is a pure optimization, so skipping it on workers is
 * side-effect-free; the main-thread/dimension-worker path is untouched.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin_RegionMerge {

    @Inject(method = "mergeWithNeighbours", at = @At("HEAD"), cancellable = true)
    private void arclight$regionSkipMerge(CallbackInfo ci) {
        if (RegionTickManager.isRegionWorker()) {
            ci.cancel();
        }
    }
}
