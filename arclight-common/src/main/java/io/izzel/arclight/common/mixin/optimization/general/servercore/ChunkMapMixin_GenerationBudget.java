/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Iterator;
import java.util.List;

/**
 * PRTS L2 Spike-A (AI-created, docs/parallel-l2-chunkgen-spike-v01.md §2): cap the
 * per-tick intake of pending chunk-generation tasks into the worldgen mailbox.
 *
 * <p>Vanilla {@code ChunkMap.runGenerationTasks} submits {@link #pendingGenerationTasks}
 * to the worldgen mailbox in full and clears the list — after a large teleport or a wide
 * exploration burst the whole backlog is handed over in a single tick, which is the main
 * thread's chunk-intake storm. With a budget we only hand over the first N tasks per tick
 * and keep the rest for the next ticks, spreading the storm into a steady rate
 * (congestion control). The intake budget is configurable via prts-features.yml
 * {@code generation-tasks-per-tick} (default 50; 0 = unlimited, vanilla behavior).</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_GenerationBudget {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-SpikeA");

    @Shadow
    @Final
    private List<ChunkGenerationTask> pendingGenerationTasks;

    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void arclight$budgetedRunGenerationTasks(CallbackInfo ci) {
        int budget = PRTSFeaturesConfig.generationTasksPerTick;
        if (budget <= 0 || this.pendingGenerationTasks.size() <= budget) {
            return;
        }
        int pending = this.pendingGenerationTasks.size();
        ci.cancel();
        int submitted = 0;
        Iterator<ChunkGenerationTask> it = this.pendingGenerationTasks.iterator();
        while (it.hasNext() && submitted < budget) {
            this.arclight$runGenerationTask(it.next());
            it.remove();
            submitted++;
        }
        LOGGER.debug("[SpikeA] generation intake capped: pending={} budget={} submitted={}", pending, budget, submitted);
    }

    @Invoker("runGenerationTask")
    abstract void arclight$runGenerationTask(ChunkGenerationTask task);
}
