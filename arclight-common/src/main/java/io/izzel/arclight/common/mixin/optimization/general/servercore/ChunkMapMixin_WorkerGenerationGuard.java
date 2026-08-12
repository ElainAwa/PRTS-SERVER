/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 兜底防线（统一异步调度的第二道闸）：worker 线程上一律不直接推进 chunk 状态
 * （applyStep），防止绕过 getChunk 拦截的漏网路径（如模组直接调用）触碰生成管线。
 * runGenerationTasks/runGenerationTask 是合法生成驱动，不在此拦截。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_WorkerGenerationGuard {

    @Inject(method = "applyStep", at = @At("HEAD"), cancellable = true)
    private void arclight$guardWorkerApplyStep(GenerationChunkHolder holder, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache,
                                               CallbackInfoReturnable<CompletableFuture<net.minecraft.world.level.chunk.ChunkAccess>> cir) {
        // 只有真实并行 worker 才被拦截；主线程在 barrier 窗口调用 applyStep 不受影响。
        if (RegionTickManager.isRegionWorker() || DimensionTickManager.isDimensionTickThread()) {
            cir.cancel();
        }
    }
}
