/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/**
 * Region block-tick bridge: exposes the private {@code ServerLevel.tickBlock} and
 * {@code ServerLevel.tickFluid} via @Invokers so the main-thread POST phase can run
 * deferred scheduled ticks without a dependency on the mixin class.
 */
public interface ServerLevelRegionBlockTickAccess {

    /** Runs one scheduled block tick (mirrors private ServerLevel.tickBlock). */
    void arclight$tickBlock(BlockPos pos, Block block);

    /** Runs one scheduled fluid tick (mirrors private ServerLevel.tickFluid). */
    void arclight$tickFluid(BlockPos pos, Fluid fluid);

    /** Runs the pending block events queue (mirrors private ServerLevel.runBlockEvents). */
    void arclight$runBlockEvents();
}
