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
 * PRTS region parallelism cross-region read counter (P3 slice 3, AI-created).
 *
 * <p>Counts block reads a region worker performs outside its own region
 * (v01 §3.5: "cross-read is the measuring instrument"). The read itself stays
 * vanilla — {@code PalettedContainer} is designed lock-free-read/locked-write
 * for async chunk generation — so no routing/placeholder is needed; the
 * counter only exposes how often the stripe boundary is crossed by reads,
 * guiding stripe tuning if the number stays high.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionCrossRead {

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"))
    private void arclight$regionCrossRead(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        RegionTickManager.countCrossRead(pos);
    }
}
