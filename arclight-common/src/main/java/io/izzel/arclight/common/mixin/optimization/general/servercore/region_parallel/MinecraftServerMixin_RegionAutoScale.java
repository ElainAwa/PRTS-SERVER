/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region auto-scale entry: evaluates region load at the RETURN of
 * {@code MinecraftServer.tickChildren}, when all workers have latched back — the
 * safe window for {@link RegionTickManager#reconfigure}. No-op when the feature is
 * disabled.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_RegionAutoScale {

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void arclight$regionAutoScale(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        RegionTickManager.evaluateAutoScale(server.getTickCount());
    }
}
