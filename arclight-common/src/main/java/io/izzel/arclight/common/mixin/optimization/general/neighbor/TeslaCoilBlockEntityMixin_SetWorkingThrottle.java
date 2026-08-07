/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This mixin adapts the runtime behavior of AE2 Lightning Tech by MOAKIEE
 * (https://github.com/MOAKIEE/AE2-Lightning-Tech), licensed under LGPL-3.0.
 * It throttles TeslaCoilBlockEntity.setWorking setBlock calls to mitigate a
 * neighbor-update storm; it does not modify ae2lt source.
 */

package io.izzel.arclight.common.mixin.optimization.general.neighbor;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Throttles TeslaCoilBlockEntity.setWorking Level.setBlock calls.
 *
 * ae2lt TeslaCoil already short-circuits when the block state is unchanged, but its
 * tickingRequest flips the working flag every tick, so setBlock still fires each tick and can
 * drive a neighbor-update storm. This limits actual setBlock to once per N ticks.
 */
@Pseudo
@Mixin(targets = "com.moakiee.ae2lt.blockentity.TeslaCoilBlockEntity", remap = false)
public abstract class TeslaCoilBlockEntityMixin_SetWorkingThrottle {

    @Unique
    private int prtsLastSetBlockTick = -10000;

    @Redirect(method = "setWorking", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean prtsThrottleSetBlock(Level instance, BlockPos pos, BlockState state, int flags) {
        if (!PRTSFeaturesConfig.ae2ltSetWorkingThrottleEnabled) {
            return instance.setBlock(pos, state, flags);
        }
        MinecraftServer server = instance.getServer();
        if (server != null) {
            int tick = server.getTickCount();
            if (tick - prtsLastSetBlockTick < PRTSFeaturesConfig.ae2ltSetWorkingThrottleMinTicks) {
                return false;
            }
            prtsLastSetBlockTick = tick;
        }
        return instance.setBlock(pos, state, flags);
    }
}
