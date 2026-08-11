/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import net.minecraft.Util;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PRTS barrier semantics P1 (AI-created, docs/parallel-barrier-semantics-v01.md §2.1):
 * make the vanilla watchdog barrier-aware.
 *
 * <p>When the main thread is waiting inside the dimension-parallel barrier
 * ({@link DimensionTickManager#inDimensionTick()}), it is doing designed parallel
 * work, not a stuck single-thread loop. The watchdog would otherwise kill the server
 * after max-tick-time (60s) because {@code nextTickTime} stops advancing. Redirecting
 * {@code getNextTickTime} to the current time while in the barrier both prevents the
 * false kill and resets the watchdog's time base (per technical review: no stale-base
 * kill right after leaving the barrier). Gate: prts-features.yml
 * {@code barrier-watchdog-aware} (default true; 0 = vanilla behavior).</p>
 */
@Mixin(ServerWatchdog.class)
public abstract class ServerWatchdogMixin_BarrierAware {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Barrier");

    private static long prts$lastWarnNanos = 0L;

    @Redirect(method = "run", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/dedicated/DedicatedServer;getNextTickTime()J"))
    private long arclight$watchdogNextTickTime(DedicatedServer server) {
        if (PRTSFeaturesConfig.barrierWatchdogAware && DimensionTickManager.inDimensionTick()) {
            long now = Util.getNanos();
            if (now - prts$lastWarnNanos > 60_000_000_000L) {
                prts$lastWarnNanos = now;
                long stalled = now - server.getNextTickTime();
                LOGGER.warn("[PRTS-Barrier] main thread waiting in parallel barrier for {}ms; watchdog suppressed",
                        stalled / 1_000_000L);
            }
            return now;
        }
        return server.getNextTickTime();
    }
}
