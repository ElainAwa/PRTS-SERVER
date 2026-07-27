package io.izzel.arclight.common.mixin.optimization.general.async_logging;

import io.izzel.arclight.common.optimization.general.async_logging.AsyncAppenderBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Startup hook for the async-logging optimization.
 * Calls AsyncAppenderBootstrap.boot() once during MinecraftServer construction,
 * after Forge has set up the root logger. The actual enable/disable decision
 * lives inside boot() (gated by the "async-logging" sub-switch), so this mixin
 * is always safe to apply.
 */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void prts$initAsyncLogging(CallbackInfo ci) {
        AsyncAppenderBootstrap.boot();
    }
}
