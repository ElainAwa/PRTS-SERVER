package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PRTS region dynamic auto-scale entry (P3 v12, AI-created).
 *
 * <p>Evaluates the region load at the RETURN of {@code MinecraftServer.tickChildren},
 * where every dimension worker (and thus every region worker embedded in the
 * dimension tick) has latched back on the main thread. That is the safe window
 * for {@link RegionTickManager#reconfigure} (docs v12 §1.1: all workers awaited,
 * next tick not started). No-op when region-parallel or auto-scale is disabled.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_RegionAutoScale {

    @Inject(method = "tickChildren", at = @At("RETURN"))
    private void arclight$regionAutoScale(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        RegionTickManager.evaluateAutoScale(server.getTickCount());
    }
}
