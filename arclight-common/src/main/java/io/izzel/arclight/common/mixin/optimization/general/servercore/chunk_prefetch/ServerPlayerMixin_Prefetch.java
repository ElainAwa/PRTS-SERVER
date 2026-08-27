/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.chunk_prefetch;

import io.izzel.arclight.common.optimization.general.servercore.ChunkPrefetcher;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A3: 挂载区块预取到玩家 tick（主线程 doTick HEAD）。
 * 预取逻辑本身全 try/catch、纯增益；关闭时零开销。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin_Prefetch {

    @Inject(method = "doTick", at = @At("HEAD"))
    private void arclight$prefetchChunks(CallbackInfo ci) {
        ChunkPrefetcher.onPlayerTick((ServerPlayer) (Object) this);
    }
}
