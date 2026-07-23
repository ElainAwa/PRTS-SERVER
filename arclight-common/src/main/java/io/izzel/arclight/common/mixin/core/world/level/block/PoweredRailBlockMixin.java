package io.izzel.arclight.common.mixin.core.world.level.block;

import io.izzel.arclight.common.optimization.PoweredRailsOptimized;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(PoweredRailBlock.class)
public class PoweredRailBlockMixin {

    @Inject(method = "updateState", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizedPoweredRail(BlockState p_55232_, Level p_55233_, BlockPos p_55234_, Block p_55235_, CallbackInfo ci) {
        if (ArclightConfig.spec().getOptimization().isExperimentalOptimizationsEnabled()) {
            PoweredRailsOptimized.customUpdateState((PoweredRailBlock) (Object) this, p_55232_, p_55233_, p_55234_);
            ci.cancel();
        }
    }

    @Inject(method = "updateState", cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    public void arclight$blockRedstone(BlockState state, Level worldIn, BlockPos pos, Block blockIn, CallbackInfo ci, boolean flag) {
        int power = flag ? 15 : 0;
        int newPower = CraftEventFactory.callRedstoneChange(worldIn, pos, power, 15 - power).getNewCurrent();
        if (newPower == power) {
            ci.cancel();
        }
    }
}
