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
 * PRTS region parallelism cross-region write guard (P3 slice 2, AI-created).
 *
 * <p>When a region worker's entity tick writes a block outside its own region
 * (e.g. an Enderman or villager placing a block across the stripe boundary),
 * the write must not touch the neighbor region's data concurrently. The write
 * is collected into the target region's update queue and applied by that
 * region's worker next tick (1-tick eventual-consistency window, see
 * RegionTickManager.applyCrossUpdates). The caller sees {@code false} (blocked)
 * for that tick and naturally retries — the same degraded-call philosophy as
 * the P2 getChunk EmptyLevelChunk fallback.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionCrossWrite {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD"), cancellable = true)
    private void arclight$regionCrossWrite(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!RegionTickManager.inRegionTick() || !RegionTickManager.isCrossWrite(pos)) {
            return;
        }
        RegionTickManager.collectCrossWrite((ServerLevel) (Object) this, pos, state, flags);
        cir.setReturnValue(false);
    }
}
