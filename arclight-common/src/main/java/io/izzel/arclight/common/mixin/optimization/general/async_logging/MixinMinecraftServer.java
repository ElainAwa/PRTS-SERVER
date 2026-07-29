package io.izzel.arclight.common.mixin.optimization.general.async_logging;

import io.izzel.arclight.common.optimization.general.async_logging.AsyncAppenderBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Startup hook for the async-logging optimization. */
@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void luminara$initAsyncLogging(CallbackInfo ci) {
        AsyncAppenderBootstrap.boot();
    }
}
