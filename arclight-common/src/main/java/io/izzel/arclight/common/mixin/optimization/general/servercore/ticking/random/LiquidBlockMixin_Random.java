/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ticking.random;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LiquidBlock.class)
public class LiquidBlockMixin_Random {

    // 流体随机 tick 已由 ServerLevel.tickChunk 执行，这里的重复 tick 可省。
    @Redirect(
            method = "isRandomlyTicking",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;isRandomlyTicking()Z"
            )
    )
    private boolean arclight$cancelDuplicateFluidTicks(FluidState fluidState) {
        if (!ServerCoreConfig.optimizations().optimizeChunkRandomTicks()) {
            return fluidState.isRandomlyTicking();
        }
        return false;
    }
}
