package io.izzel.arclight.common.mixin.core.world.level.block;

import io.izzel.arclight.common.optimization.PoweredRailBlockBridge;
import io.izzel.arclight.common.optimization.PoweredRailsOptimized;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(PoweredRailBlock.class)
public abstract class PoweredRailBlockMixin implements PoweredRailBlockBridge {

    @Shadow protected abstract boolean findPoweredRailSignal(Level level, BlockPos pos, BlockState state, boolean travelDirection, int depth);

    private static final Logger LUMINARA$RAIL_LOGGER = LogManager.getLogger("Luminara-PoweredRails");
    private static volatile boolean luminara$activeLogged = false;

    @Override
    public boolean luminara$findPoweredRailSignal(Level level, BlockPos pos, BlockState state, boolean travelDirection, int depth) {
        return this.findPoweredRailSignal(level, pos, state, travelDirection, depth);
    }

    @Inject(method = "updateState", at = @At("HEAD"), cancellable = true)
    private void luminara$optimizedPoweredRail(BlockState state, Level level, BlockPos pos, Block block, CallbackInfo ci) {
        boolean enabled = ArclightConfig.spec().getOptimization().isOptimizePoweredRails();
        if (!luminara$activeLogged) {
            luminara$activeLogged = true;
            LUMINARA$RAIL_LOGGER.info("[Luminara-PoweredRails] powered-rail optimization mixin active (enabled={})", enabled);
        }
        if (enabled) {
            PoweredRailsOptimized.customUpdateState((PoweredRailBlock) (Object) this, state, level, pos);
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
