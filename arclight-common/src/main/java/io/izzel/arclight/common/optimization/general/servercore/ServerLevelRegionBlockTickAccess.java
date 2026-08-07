package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * PRTS region block-tick bridge (P3 v04, AI-created).
 *
 * <p>Implemented by {@code ServerLevelMixin_RegionBlockTick} via an @Invoker to
 * expose the private {@code ServerLevel.tickBlock} so region workers can run
 * scheduled block ticks without a compile-time dependency on the mixin class.</p>
 */
public interface ServerLevelRegionBlockTickAccess {

    /** Runs one scheduled block tick (mirrors private ServerLevel.tickBlock). */
    void arclight$tickBlock(BlockPos pos, Block block);
}
