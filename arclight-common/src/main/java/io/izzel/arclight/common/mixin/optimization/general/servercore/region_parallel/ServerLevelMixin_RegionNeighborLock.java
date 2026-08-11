/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Serializes vanilla {@code NeighborUpdater} calls: the underlying
 * {@code CollectingNeighborUpdater} is stateful and single-threaded, and concurrent
 * region-worker block ticks corrupt it. All funnel call sites (the two
 * {@code updateNeighborsAt} variants plus the {@code shapeUpdate} call) are wrapped
 * in a synchronized block.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_RegionNeighborLock {

    @Redirect(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V"))
    private void arclight$lockNeighborUpdate(NeighborUpdater updater, BlockPos pos, Block block, Direction dir) {
        synchronized (updater) {
            updater.updateNeighborsAtExceptFromFacing(pos, block, dir);
        }
    }

    @Redirect(method = "updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;updateNeighborsAtExceptFromFacing(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/Direction;)V"))
    private void arclight$lockNeighborUpdate2(NeighborUpdater updater, BlockPos pos, Block block, Direction dir) {
        synchronized (updater) {
            updater.updateNeighborsAtExceptFromFacing(pos, block, dir);
        }
    }
}
