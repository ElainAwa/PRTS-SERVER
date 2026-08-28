/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 替换原版 replaceProtoChunk 的遍历：跳过 null / 未完成 / 非 ProtoChunk 槽位。
 * 原版对所有槽位 requireNonNull 且非 ProtoChunk 结果抛 ISE；M2 与 modernfix
 * surrogate 并发下，未物化的槽位是合法的（completeFuture 完成时 CAS 补位），
 * 预填已完成 future 会阻塞真实完成（ImposterProtoChunk 泄漏到 FULL future）。
 */
@Mixin(GenerationChunkHolder.class)
public abstract class GenerationChunkHolderMixin_ReplaceProtoChunkSafe {

    @Shadow
    @Final
    private AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> futures;

    @Shadow
    @Final
    private static ChunkResult<ChunkAccess> NOT_DONE_YET;

    @Inject(method = "replaceProtoChunk", at = @At("HEAD"), cancellable = true)
    private void arclight$replaceProtoChunkSkipNulls(ImposterProtoChunk chunk, CallbackInfo ci) {
        CompletableFuture<ChunkResult<ChunkAccess>> replacement =
                CompletableFuture.completedFuture(ChunkResult.of(chunk));
        for (int i = 0; i < this.futures.length(); i++) {
            CompletableFuture<ChunkResult<ChunkAccess>> future = this.futures.get(i);
            if (future == null) {
                continue; // 未物化槽位：跳过（completeFuture 完成时补位）
            }
            ChunkAccess chunkAccess = future.getNow(NOT_DONE_YET).orElse(null);
            if (chunkAccess instanceof ProtoChunk) {
                if (!this.futures.compareAndSet(i, future, replacement)) {
                    throw new IllegalStateException("Future changed by other thread while trying to replace it");
                }
            }
        }
        ci.cancel();
    }
}
