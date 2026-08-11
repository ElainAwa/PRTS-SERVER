/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import org.bukkit.Server;
import org.bukkit.plugin.SimplePluginManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets Bukkit events fired from a dimension tick worker pass Paper's main-thread
 * check: {@code SimplePluginManager.callEvent} throws when
 * {@code !server.isPrimaryThread()}. Deferring every event is not viable, so the
 * single isPrimaryThread check reports true on workers — safe because plugin
 * listeners run on the same thread as the entity tick.
 */
@Mixin(value = SimplePluginManager.class, remap = false)
public abstract class SimplePluginManagerMixin_DimParallel {

    @Redirect(method = "callEvent",
        at = @At(value = "INVOKE", target = "Lorg/bukkit/Server;isPrimaryThread()Z", ordinal = 1))
    private boolean arclight$dimParallelPrimaryThread(Server server) {
        if (!DimensionTickManager.inDimensionTick() && !RegionTickManager.inRegionTick()) {
            return server.isPrimaryThread();
        }
        return true;
    }
}
