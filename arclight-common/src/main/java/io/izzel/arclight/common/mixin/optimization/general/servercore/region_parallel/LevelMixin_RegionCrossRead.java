/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts block reads a region worker performs outside its own region. The read is
 * left vanilla (PalettedContainer is lock-free-read/locked-write), so the counter
 * only exposes how often the stripe boundary is crossed, guiding stripe tuning.
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionCrossRead {

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"))
    private void arclight$regionCrossRead(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        RegionTickManager.countCrossRead(pos);
    }
}
