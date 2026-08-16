package io.izzel.arclight.neoforge.mixin.eventbridge;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.eventbridge.EventShortcircuitStats;
import io.izzel.arclight.neoforge.mod.event.EventBusQuery;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P1-4 NeighborNotifyEvent short-circuit for the {@code ServerLevel} override of
 * {@code updateNeighborsAt}: the patched method fires onNeighborNotify, pops the
 * {@code isCanceled()} result and continues with the neighbor updater, so skipping
 * the fire changes nothing observable. (The {@code updateNeighborsAtExceptFromFacing}
 * variant respects cancellation — it is deliberately left untouched; with zero
 * listeners the event is never cancelled anyway, which the cached default instance
 * preserves.)
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_NeighborNotifyShortcircuit {

    @Unique
    private static final BlockEvent.NeighborNotifyEvent CACHED_NOTIFY =
            new BlockEvent.NeighborNotifyEvent(null, null, null, EnumSet.noneOf(Direction.class), false);

    @Redirect(method = "updateNeighborsAt",
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/neoforged/neoforge/event/EventHooks;onNeighborNotify(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/EnumSet;Z)Lnet/neoforged/neoforge/event/level/BlockEvent$NeighborNotifyEvent;"))
    private BlockEvent.NeighborNotifyEvent arclight$onNeighborNotify(Level level, BlockPos pos, BlockState state,
                                                                     EnumSet<Direction> notifiedSides, boolean forceRedstoneUpdate,
                                                                     BlockPos posArg, Block block) {
        if (PRTSFeaturesConfig.eventShortcircuitNeighborNotifyEnabled
                && !EventBusQuery.hasListeners(BlockEvent.NeighborNotifyEvent.class)) {
            EventShortcircuitStats.increment("neighborNotifySkipped");
            return CACHED_NOTIFY;
        }
        return EventHooks.onNeighborNotify(level, pos, state, notifiedSides, forceRedstoneUpdate);
    }
}
