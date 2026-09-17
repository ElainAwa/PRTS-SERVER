/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.optimization.chunksystem.ChunkDemandQueue;
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

    /**
     * 槽位清空计数（换票/卸载会 failAndClear 掉未完成的状态 future）。
     * 状态机任务据此判断"门通过之后世界是否变了"：没变就不必重做全锥物化校验
     * （JFR 实测全锥校验的 getChunkIfPresentUnchecked 环扫占 ~12% 采样）。
     */
    @Inject(method = "failAndClearPendingFuture", at = @At("HEAD"))
    private void arclight$bumpFutureClearEpoch(int status,
                                               java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<ChunkAccess>> future,
                                               CallbackInfo ci) {
        io.izzel.arclight.common.optimization.chunksystem.ChunkSystemDriver.futureCleared();
    }

    @Inject(method = "completeFuture", at = @At("RETURN"))
    private void arclight$notifyChunkComplete(ChunkStatus status, ChunkAccess chunk, CallbackInfo ci) {
        if (status != ChunkStatus.FULL || !(chunk instanceof LevelChunk levelChunk) || !(levelChunk.level instanceof ServerLevel level)) {
            return;
        }
        ChunkDemandQueue.completeChunk(level, this.pos.x, this.pos.z, chunk);
    }
}
