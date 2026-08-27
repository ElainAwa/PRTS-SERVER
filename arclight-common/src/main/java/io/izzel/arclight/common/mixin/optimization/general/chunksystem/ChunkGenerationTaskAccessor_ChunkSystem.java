/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.PRTSChunkSystemTaskAware;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * M2.1 状态机驱动器访问面（见 {@link PRTSChunkSystemTaskAware}）。
 */
@Mixin(ChunkGenerationTask.class)
public interface ChunkGenerationTaskAccessor_ChunkSystem extends PRTSChunkSystemTaskAware {

    @Override
    @Accessor("cache")
    StaticCache2D<GenerationChunkHolder> prts$cache();

    @Override
    @Invoker("releaseClaim")
    void prts$releaseClaim();

    @Override
    @Accessor("markedForCancellation")
    boolean prts$isMarkedForCancellation();
}
