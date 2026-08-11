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
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk-level cross-region write guard: redstone-wire power updates write
 * {@code LevelChunk.setBlockState} directly, bypassing the Level.setBlock guard.
 * This collects such writes into the target region's update queue instead (1-tick
 * window), mirroring LevelMixin_RegionCrossWrite.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin_RegionCrossWrite {

    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("HEAD"), cancellable = true)
    private void arclight$chunkCrossWrite(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        if (!RegionTickManager.inRegionTick() || !RegionTickManager.isCrossWrite(pos)) {
            return;
        }
        if (this.level instanceof ServerLevel) {
            RegionTickManager.collectCrossWrite((ServerLevel) this.level, pos, state, 3);
        }
        cir.setReturnValue(this.level.getBlockState(pos));
    }
}
