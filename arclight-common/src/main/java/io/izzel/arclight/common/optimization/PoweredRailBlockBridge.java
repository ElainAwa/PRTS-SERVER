package io.izzel.arclight.common.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Bridge exposing the protected {@code PoweredRailBlock.findPoweredRailSignal} to */
public interface PoweredRailBlockBridge {

    boolean prts$findPoweredRailSignal(Level level, BlockPos pos, BlockState state, boolean travelDirection, int depth);
}
