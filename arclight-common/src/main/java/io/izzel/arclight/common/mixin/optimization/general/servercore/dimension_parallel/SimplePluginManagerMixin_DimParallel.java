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
 * PRTS dimension parallelism (P2 experiment, AI-created): let Bukkit events fired
 * from a dimension tick worker pass Paper's main-thread check.
 *
 * <p>{@code SimplePluginManager.callEvent} throws
 * {@code "<Event> cannot be triggered asynchronously from another thread"} when
 * {@code !server.isPrimaryThread()} (the sync-event branch, ordinal 1; the async-event
 * branch keeps its semantics). On a dimension tick worker the vanilla level.tick fires
 * many Bukkit events (EntityAirChangeEvent, EntityDamageEvent, ...) — deferring each one
 * is not viable. Instead we report "primary thread" for that single check, so the event
 * is dispatched normally and plugin listeners run on the worker thread — the same thread
 * as the entity tick, i.e. safe for same-dimension state (the documented P2 boundary,
 * docs/parallel-phase2-dimension-parallelism-v01.md §5). The deferred entity load/unload
 * queue stays for those two notification events.</p>
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
