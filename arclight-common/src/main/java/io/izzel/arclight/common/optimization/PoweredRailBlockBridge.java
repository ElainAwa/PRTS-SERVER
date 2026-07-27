package io.izzel.arclight.common.optimization;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bridge exposing the protected {@code PoweredRailBlock.findPoweredRailSignal} to
 * {@link PoweredRailsOptimized}.
 * <p>
 * 1.20.1 exposed the method via an accesstransformer.cfg entry; the 1.21.1 (NeoForge)
 * tree has no MC-class access transformer infrastructure (bukkit.at only covers
 * CraftBukkit classes). Implemented by the core PoweredRailBlockMixin via @Shadow —
 * same holder/bridge pattern as NearbyPlayerIndexHolder, deliberately kept OUT of the
 * mixin package so ordinary code may reference it safely.
 */
public interface PoweredRailBlockBridge {

    boolean prts$findPoweredRailSignal(Level level, BlockPos pos, BlockState state, boolean travelDirection, int depth);
}
