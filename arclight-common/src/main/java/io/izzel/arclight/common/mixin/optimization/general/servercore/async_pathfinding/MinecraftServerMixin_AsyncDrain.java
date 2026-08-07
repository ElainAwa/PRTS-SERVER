package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.AsyncPathfindingManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PRTS async pathfinding tick drain (P1 experiment, AI-created).
 * Drains and applies async pathfinding results on the server thread at the end
 * of every server tick, so results are applied even when no new pathfinding
 * request happens in the meantime (low-frequency navigation would otherwise let
 * results expire in the queue).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_AsyncDrain {

    @Shadow
    public abstract int getTickCount();

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void arclight$drainAsyncPathfinding(CallbackInfo ci) {
        AsyncPathfindingManager.drainIfNeeded(this.getTickCount());
    }
}
