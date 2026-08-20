/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.ChunkLoadStats;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only worldgen submission counter (S2.75 P0). Records every generation task
 * submission regardless of whether the generation budget gate is active, so
 * {@code ChunkLoading genSubmitted}/{@code genSubmitMs} reflect real generation
 * pressure even under the default configuration.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_GenerationStat {

    private static final ThreadLocal<Long> START = new ThreadLocal<>();

    @Inject(method = "runGenerationTask", at = @At("HEAD"))
    private void arclight$genStart(ChunkGenerationTask task, CallbackInfo ci) {
        START.set(System.nanoTime());
    }

    @Inject(method = "runGenerationTask", at = @At("RETURN"))
    private void arclight$genEnd(ChunkGenerationTask task, CallbackInfo ci) {
        Long start = START.get();
        START.remove();
        ChunkLoadStats.generationSubmitted(start == null ? 0L : System.nanoTime() - start);
    }
}
