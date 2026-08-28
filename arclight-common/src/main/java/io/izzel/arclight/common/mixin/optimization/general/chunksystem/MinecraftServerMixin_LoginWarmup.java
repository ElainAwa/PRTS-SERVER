/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.LoginWarmup;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 进服热点预热入口：首次 tick 铺开队列，之后每 tick 消费预算
 * （0 玩家同样生效，主线程直接 getChunk 不受 tickChunks 门控影响）。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_LoginWarmup {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void prts$loginWarmupSchedule(CallbackInfo ci) {
        LoginWarmup.schedule((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void prts$loginWarmupTick(CallbackInfo ci) {
        LoginWarmup.tick((MinecraftServer) (Object) this);
    }
}
