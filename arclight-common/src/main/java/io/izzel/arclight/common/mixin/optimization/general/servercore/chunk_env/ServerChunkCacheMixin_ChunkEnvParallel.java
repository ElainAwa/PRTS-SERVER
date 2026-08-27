/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.chunk_env;

import io.izzel.arclight.common.optimization.general.servercore.ChunkEnvParallelScheduler;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

/**
 * 区块环境 tick 并行入口:tickChunks 内收集各 chunk 的随机/流体 tick,
 * 广播前统一提交并行执行(仅主线程路径;维度 worker 路径暂不并行)。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_ChunkEnvParallel {

    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "tickChunks", at = @At("HEAD"))
    private void arclight$chunkEnvBegin(CallbackInfo ci) {
        ChunkEnvParallelScheduler.begin(this.level.getServer() != null && this.level.getServer().isSameThread());
    }

    @Redirect(method = "tickChunks",
        at = @At(value = "INVOKE",
                target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void arclight$collectChunkEnvTick(ServerLevel level, LevelChunk chunk, int randomTickSpeed) {
        if (!ChunkEnvParallelScheduler.collect(level, chunk, randomTickSpeed)) {
            level.tickChunk(chunk, randomTickSpeed);
        }
    }

    @Redirect(method = "tickChunks",
        at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void arclight$flushChunkEnvTicks(List<?> list, Consumer<Object> consumer) {
        ChunkEnvParallelScheduler.flush();
        list.forEach(consumer);
    }
}
