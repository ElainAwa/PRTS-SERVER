/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PRTS region parallelism: serialize the {@code shapeUpdate} funnel of vanilla
 * {@code NeighborUpdater} from {@code Level.neighborShapeChanged} (see
 * {@link ServerLevelMixin_RegionNeighborLock} for the rationale; the underlying
 * {@code CollectingNeighborUpdater} is single-threaded state corrupted by
 * concurrent region-worker block ticks).
 */
@Mixin(Level.class)
public abstract class LevelMixin_RegionNeighborShapeLock {

    @Redirect(method = "neighborShapeChanged(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;II)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/redstone/NeighborUpdater;shapeUpdate(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;II)V"))
    private void arclight$lockShapeUpdate(NeighborUpdater updater, Direction dir, BlockState state, BlockPos pos, BlockPos fromPos, int a, int b) {
        synchronized (updater) {
            updater.shapeUpdate(dir, state, pos, fromPos, a, b);
        }
    }
}
