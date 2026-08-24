/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 生成完成通知：chunk 状态推进完成后唤醒所有等待该 chunk 的异步调用方
 * （统一异步调度的回调桥）。
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin_CompleteNotify {

    @Shadow(remap = false)
    protected ChunkPos pos;

    @Inject(method = "completeFuture", at = @At("RETURN"))
    private void arclight$notifyChunkComplete(ChunkStatus status, ChunkAccess chunk, CallbackInfo ci) {
        if (status != ChunkStatus.FULL || !(chunk instanceof LevelChunk levelChunk) || !(levelChunk.level instanceof ServerLevel level)) {
            return;
        }
        ChunkDemandQueue.completeChunk(level, this.pos.x, this.pos.z, chunk);
    }
}
