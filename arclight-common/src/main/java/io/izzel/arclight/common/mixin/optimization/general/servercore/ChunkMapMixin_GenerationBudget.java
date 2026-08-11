/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
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
 * 限制区块生成任务的提交速度，防止大量区块同时生成时主线程卡顿尖峰。
 * 双闸门：每 tick 最多提交 N 个；滚动 2s 窗口内最多提交 M 个。
 * 配置见 prts-features.yml（generation-tasks-per-tick / chunkgen-inflight-limit，0 = 关闭）。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_GenerationBudget {

    /** 滚动提交时间戳（纳秒）环形缓冲，仅记录提交不追踪完成，故永不卡死生成。 */
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
    }

    /** 统计最近 windowNanos（2s）内的提交次数。 */
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
