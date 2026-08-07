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
        if (!ServerCoreConfig.features().optimizeChunkRandomTicks()) {
            return fluidState.isRandomlyTicking();
        }
        return false;
    }
}
