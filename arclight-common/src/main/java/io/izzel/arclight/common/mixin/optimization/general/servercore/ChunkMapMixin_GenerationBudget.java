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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Iterator;
import java.util.List;

/**
 * PRTS chunkgen spike control (AI-created, docs/parallel-barrier-semantics-v01.md §2.3):
 * two gates on chunk generation.
 *
 * <p>Gate 1 (intake budget, a2ce003): cap how many pending generation tasks the main
 * thread hands to the worldgen mailbox per tick (generation-tasks-per-tick, default 50).
 *
 * <p>Gate 2 (submission time-window, v01.2): cap how many tasks are submitted within a
 * rolling 2s window (chunkgen-inflight-limit, default 64). Worldgen workers then produce
 * completions at a steady rate instead of a storm, so the main thread can drain
 * completion callbacks without the multi-second spike (a burst of 1000+ forceload chunks
 * used to batch into a 1.5-2.3s tick). The window is submission-only — it never tracks
 * completion, so it cannot wedge the intake gate. Both gates are in prts-features.yml;
 * 0 on either = that gate off.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_GenerationBudget {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-SpikeA");

    /** Rolling submission timestamps (nanos), ring buffer; only tracks submissions. */
    @Unique
    private static final long[] prts$submitTimes = new long[256];

    @Unique
    private static int prts$submitIndex = 0;

    @Shadow
    @Final
    private List<ChunkGenerationTask> pendingGenerationTasks;

    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void arclight$budgetedRunGenerationTasks(CallbackInfo ci) {
        int budget = PRTSFeaturesConfig.generationTasksPerTick;
        int limit = PRTSFeaturesConfig.chunkgenInflightLimit;
        if (budget <= 0 && limit <= 0) {
            return;
        }
        boolean overBudget = budget > 0 && this.pendingGenerationTasks.size() > budget;
        boolean overWindow = limit > 0 && prts$submittedInWindow(2_000_000_000L) >= limit;
        if (!overBudget && !overWindow) {
            return;
        }
        int pending = this.pendingGenerationTasks.size();
        ci.cancel();
        int submitted = 0;
        Iterator<ChunkGenerationTask> it = this.pendingGenerationTasks.iterator();
        while (it.hasNext()) {
            if (budget > 0 && submitted >= budget) {
                break;
            }
            if (limit > 0 && prts$submittedInWindow(2_000_000_000L) >= limit) {
                break;
            }
            this.arclight$runGenerationTask(it.next());
            it.remove();
            prts$submitTimes[prts$submitIndex++ % prts$submitTimes.length] = System.nanoTime();
            submitted++;
        }
        LOGGER.debug("[SpikeA] generation intake capped: pending={} submitted={} window={} (budget={} limit={})",
                pending, submitted, prts$submittedInWindow(2_000_000_000L), budget, limit);
    }

    /** Count submissions inside the last windowNanos (2s window). */
    @Unique
    private static int prts$submittedInWindow(long windowNanos) {
        long now = System.nanoTime();
        int count = 0;
        for (long t : prts$submitTimes) {
            if (t != 0L && now - t <= windowNanos) {
                count++;
            }
        }
        return count;
    }

    @Invoker("runGenerationTask")
    abstract void arclight$runGenerationTask(ChunkGenerationTask task);
}
