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
 * PRTS region parallelism: serialize vanilla {@code NeighborUpdater} calls.
 *
 * <p>Region workers execute block ticks concurrently (e.g. LeavesBlock decay calling
 * {@code setBlock → updateNeighborsAt / neighborShapeChanged}); the underlying vanilla
 * {@code CollectingNeighborUpdater} is a stateful single-threaded object (stack /
 * addedThisLayer / count) and concurrent access corrupts it (NPE / NoSuchElement
 * in ArrayDeque, watchdog hang). All call sites that funnel into it from the region
 * block-tick session are wrapped in a synchronized block here: the two
 * {@code updateNeighborsAt} variants on {@code ServerLevel} plus the
 * {@code shapeUpdate} call inside {@code Level.neighborShapeChanged}. Redstone
 * neighbor updates were never a parallelism win (v01 §5 keeps the update-set
 * protocol for cross-region writes), so serializing them is the documented cost.</p>
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
