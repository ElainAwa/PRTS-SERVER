package io.izzel.arclight.common.mixin.optimization.general.async;

import io.izzel.arclight.common.mixin.optimization.general.MpemThreadManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentLinkedQueue;

public class AsyncRedstoneManager {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-MPEM-AsyncRedstone");
    private static boolean initialized = false;
    private static final ConcurrentLinkedQueue<RedstoneUpdate> pendingUpdates = new ConcurrentLinkedQueue<>();

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncRedstoneEnabled()) return;


    }

    public static void shutdown() {
        if (!initialized) return;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncRedstoneEnabled() || !initialized) return;


        processPendingUpdates();
    }

    private static void processPendingUpdates() {
        if (!initialized || !MpemThreadManager.isHealthy()) return;

        RedstoneUpdate update;
        while ((update = pendingUpdates.poll()) != null) {
            final RedstoneUpdate finalUpdate = update;

            MpemThreadManager.runAsync(() -> processRedstoneUpdate(finalUpdate))
                    .exceptionally(throwable -> {
                        LOGGER.warn("Error processing async redstone update at {}", finalUpdate.pos, throwable);
                        return null;
                    });
        }
    }

    private static void processRedstoneUpdate(RedstoneUpdate update) {
        try {
            ServerLevel level = update.level;
            BlockPos pos = update.pos;
            BlockState state = level.getBlockState(pos);


            if (!(state.getBlock() instanceof RedStoneWireBlock)) {
                return;
            }


            int power = calculateRedstoneSignal(level, pos, state);


            level.getServer().execute(() -> {
                try {
                    if (level.getBlockState(pos) == state) {

                        level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, power), 2);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Error applying async redstone update", e);
                }
            });

        } catch (Exception e) {
            LOGGER.warn("Error in async redstone processing", e);
        }
    }

    private static int calculateRedstoneSignal(ServerLevel level, BlockPos pos, BlockState state) {
        try {

            int maxPower = 0;


            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos adjacentPos = pos.relative(direction);
                BlockState adjacentState = level.getBlockState(adjacentPos);

                if (adjacentState.getBlock() instanceof RedStoneWireBlock) {
                    int adjacentPower = adjacentState.getValue(RedStoneWireBlock.POWER);
                    maxPower = Math.max(maxPower, adjacentPower - 1);
                }
            }

            return Math.max(0, Math.min(15, maxPower));

        } catch (Exception e) {
            LOGGER.warn("Error calculating redstone signal", e);
            return 0;
        }
    }

    public static void scheduleRedstoneUpdate(ServerLevel level, BlockPos pos) {
        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncRedstoneEnabled() || !initialized) return;

        if (level.players().stream().anyMatch(p -> p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 1024)) {
            return;
        }

        pendingUpdates.offer(new RedstoneUpdate(level, pos));
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isHealthy() {
        return initialized && MpemThreadManager.isHealthy();
    }

    private static class RedstoneUpdate {
        final ServerLevel level;
        final BlockPos pos;

        RedstoneUpdate(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos.immutable();
        }
    }
}
