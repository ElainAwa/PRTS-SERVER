/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cross-region write guard: when a region worker's entity tick writes a block
 * outside its own region, the write is collected into the target region's update
 * queue and applied by that region's worker next tick (1-tick window). The caller
 * sees {@code false} (blocked) for that tick and naturally retries.
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionCrossWrite {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD"), cancellable = true)
    private void arclight$regionCrossWrite(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!RegionTickManager.isRegionWorker() || !RegionTickManager.isCrossWrite(pos)) {
            return;
        }
        RegionTickManager.collectCrossWrite((ServerLevel) (Object) this, pos, state, flags);
        cir.setReturnValue(false);
    }
}
