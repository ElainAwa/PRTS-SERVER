/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * replaceProtoChunk 对全部 futures 槽位 requireNonNull；并发下未物化的槽位
 * （从未请求的状态）为 null → NPE。HEAD 预填空槽位为 imposter 的已完成
 * future：原版循环本就要把每个 ProtoChunk 槽位替换成 imposter future，
 * 预填后语义与原版意图一致（空槽位=无 future 可替换）。
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin_ReplaceProtoChunkSafe {

    @Shadow
    @Final
    private AtomicReferenceArray<CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>> futures;

    @Inject(method = "replaceProtoChunk", at = @At("HEAD"))
    private void arclight$fillNullFutures(ImposterProtoChunk chunk, CallbackInfo ci) {
        CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> sentinel =
                CompletableFuture.completedFuture(ChunkResult.of(chunk));
        for (int i = 0; i < this.futures.length(); i++) {
            if (this.futures.get(i) == null) {
                this.futures.set(i, sentinel);
            }
        }
    }
}
