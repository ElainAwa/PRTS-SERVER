/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.neighbor;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Neighbor-update circuit breaker.
 *
 * Some mods (e.g. AE2 Lightning Tech TeslaCoil) flip a block state every tick and trigger a
 * chain of neighbor updates that recurses within a single server tick, eventually exceeding the
 * watchdog timeout and killing the server. This mixin caps neighbor updates dispatched per tick;
 * once the cap is exceeded the excess updates are dropped (not dispatched) and a warning is logged,
 * so the server survives the storm instead of being watchdog-killed.
 */
@Mixin(Level.class)
public abstract class LevelMixin_NeighborUpdateCircuitBreaker {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Optimization");
    private static long prtsNbCount = 0L;
    private static long prtsNbLastTick = -1L;
    private static long prtsNbWarnNanos = 0L;

    @Inject(method = "updateNeighborsAt", at = @At("HEAD"), cancellable = true)
    private void prtsNbBreak(BlockPos pos, Block block, CallbackInfo ci) {
        if (!PRTSFeaturesConfig.neighborUpdateBreakerEnabled) {
            return;
        }
        MinecraftServer server = ((Level) (Object) this).getServer();
        if (server == null) {
            return;
        }
        long tick = server.getTickCount();
        if (tick != prtsNbLastTick) {
            prtsNbCount = 0L;
            prtsNbLastTick = tick;
        }
        prtsNbCount++;
        if (prtsNbCount > PRTSFeaturesConfig.neighborUpdateBreakerMaxPerTick) {
            ci.cancel();
            long now = System.nanoTime();
            if (now - prtsNbWarnNanos > 5000000000L) {
                prtsNbWarnNanos = now;
                LOGGER.warn("[PRTS] Neighbor-update storm suppressed in tick {}: more than {} updates queued "
                        + "(likely a mod neighbor-update loop). Excess updates dropped to protect the server "
                        + "from watchdog kill.", tick, PRTSFeaturesConfig.neighborUpdateBreakerMaxPerTick);
            }
        }
    }
}
