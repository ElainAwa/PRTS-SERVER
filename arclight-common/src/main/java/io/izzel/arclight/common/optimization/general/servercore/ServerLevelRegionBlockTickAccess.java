/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * Region block-tick bridge: exposes the private {@code ServerLevel.tickBlock} via
 * an @Invoker so region workers can run scheduled block ticks without a dependency
 * on the mixin class.
 */
public interface ServerLevelRegionBlockTickAccess {

    /** Runs one scheduled block tick (mirrors private ServerLevel.tickBlock). */
    void arclight$tickBlock(BlockPos pos, Block block);
}
