/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemStats;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 状态步遥测（M1 阶段一仪表化，零语义变化）：测量 {@code ChunkMap.applyStep}
 * 内联耗时并按目标状态分桶，同时统计 FEATURES 段的并发度（H1 锁串行判据）。
 * 调度器关闭时不记录（与原版路径无交互）。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_StepTelemetry {

    @Inject(method = "applyStep", at = @At("HEAD"))
    private void prts$stepBegin(GenerationChunkHolder holder, ChunkStep step,
                                StaticCache2D<GenerationChunkHolder> cache,
                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemStats.stepBegin(step.targetStatus());
    }

    @Inject(method = "applyStep", at = @At("RETURN"))
    private void prts$stepEnd(GenerationChunkHolder holder, ChunkStep step,
                              StaticCache2D<GenerationChunkHolder> cache,
                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemStats.stepEnd(step.targetStatus());
    }
}
