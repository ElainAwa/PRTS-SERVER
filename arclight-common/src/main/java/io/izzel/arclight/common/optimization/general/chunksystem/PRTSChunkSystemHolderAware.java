/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M2.1 状态机驱动器对 {@link GenerationChunkHolder} 的访问面（mixin 实现）。
 * future 物化与步骤推进都走原版私有/包私有路径，保持 {@code acquireStatusBump}
 * CAS 去重与 {@code completeFuture} 兼容语义：重复推进者拿到既有 future 等待。
 */
public interface PRTSChunkSystemHolderAware {

    /** 原版 {@code getOrCreateFuture}：物化状态 future 槽位（幂等）。 */
    CompletableFuture<ChunkResult<ChunkAccess>> prts$getOrCreateFuture(ChunkStatus status);

    /** 原版 {@code applyStep}：CAS 抢占执行权，抢不到返回既有 future。 */
    CompletableFuture<ChunkResult<ChunkAccess>> prts$applyStep(ChunkStep step, GeneratingChunkMap chunkMap,
                                                               StaticCache2D<GenerationChunkHolder> cache);

    /** 原版 {@code task} 字段（当前生成任务引用，读为原子操作）。 */
    AtomicReference<ChunkGenerationTask> prts$task();
}
