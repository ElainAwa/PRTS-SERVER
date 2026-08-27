/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.PRTSChunkSystemHolderAware;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M2.1 状态机驱动器访问面（见 {@link PRTSChunkSystemHolderAware}）。
 */
@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccessor_ChunkSystem extends PRTSChunkSystemHolderAware {

    @Override
    @Invoker("getOrCreateFuture")
    CompletableFuture<ChunkResult<ChunkAccess>> prts$getOrCreateFuture(ChunkStatus status);

    @Override
    @Invoker("applyStep")
    CompletableFuture<ChunkResult<ChunkAccess>> prts$applyStep(ChunkStep step, GeneratingChunkMap chunkMap,
                                                               StaticCache2D<GenerationChunkHolder> cache);

    @Override
    @Accessor("task")
    AtomicReference<ChunkGenerationTask> prts$task();
}
