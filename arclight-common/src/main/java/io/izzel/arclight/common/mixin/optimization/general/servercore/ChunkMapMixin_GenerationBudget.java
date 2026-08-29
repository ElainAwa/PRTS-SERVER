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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
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

    /** 卫兵告警节流：至少间隔 10s 才打一次日志，避免风暴期刷屏。 */
    @Unique
    private static long prts$guardLogCooldownNanos = 0L;

    /** GC 压力采样（30s 滑动窗口）：上一窗口 GC 时间占比，叠加进卫兵提前降速。 */
    @Unique
    private static long prts$lastGcSampleNanos = 0L;
    @Unique
    private static long prts$lastGcTotalMs = 0L;
    @Unique
    private static double prts$gcRatio = 0.0;

    @Shadow
    @Final
    private List<ChunkGenerationTask> pendingGenerationTasks;

    /** 主线程事件循环：managedBlock 会泵它，阻塞 getChunk 期间也能推进提交。 */
    @Shadow
    @Final
    private net.minecraft.util.thread.BlockableEventLoop<Runnable> mainThreadExecutor;

    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void arclight$budgetedRunGenerationTasks(CallbackInfo ci) {
        int budget = PRTSFeaturesConfig.generationTasksPerTick;
        int limit = PRTSFeaturesConfig.chunkgenInflightLimit;
        if (budget <= 0 && limit <= 0) {
            return;
        }
        if (PRTSFeaturesConfig.generationMemoryGuardEnabled) {
            double ratio = (double) Runtime.getRuntime().totalMemory()
                    / Runtime.getRuntime().maxMemory();
            prts$sampleGcPressure();
            boolean gcPressure = prts$gcRatio > 0.30 && ratio > 0.55;
            if (gcPressure && prts$guardLogCooldownNanos == 0L
                    || gcPressure && System.nanoTime() - prts$guardLogCooldownNanos > 30_000_000_000L) {
                prts$guardLogCooldownNanos = System.nanoTime();
                org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkGen")
                        .warn("[chunk-gen] memory guard GC pressure (gc={}% committed={}%) throttling early",
                                (int) (prts$gcRatio * 100), (int) (ratio * 100));
            }
            if (ratio >= PRTSFeaturesConfig.generationMemoryGuardPauseRatio) {
                // 暂停提交：等 GC 追回 committed 后再恢复，防加载风暴把堆顶满 Xmx
                if (prts$guardLogCooldownNanos == 0L
                        || System.nanoTime() - prts$guardLogCooldownNanos > 10_000_000_000L) {
                    prts$guardLogCooldownNanos = System.nanoTime();
                    org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkGen")
                            .warn("[chunk-gen] memory guard PAUSE submissions (committed={}% of max)",
                                    (int) (ratio * 100));
                }
                ci.cancel();
                return;
            }
            double throttleRatio = gcPressure
                    ? Math.min(PRTSFeaturesConfig.generationMemoryGuardThrottleRatio, 0.55)
                    : PRTSFeaturesConfig.generationMemoryGuardThrottleRatio;
            if (ratio >= throttleRatio) {
                budget = Math.max(2, budget / 2);
                limit = Math.max(2, limit / 2);
            }
        }
        boolean overBudget = budget > 0 && this.pendingGenerationTasks.size() > budget;
        boolean overWindow = limit > 0 && prts$submittedInWindow(2_000_000_000L) >= limit;
        if (!overBudget && !overWindow) {
            return;
        }
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
        // 有剩余任务且本轮有提交：经主线程 executor 排后续 drain。主线程阻塞在
        // getChunk 的 managedBlock 时 waitForTasks 会泵 executor，任务得以继续提交，
        // 否则提交只随 tick 链推进，阻塞期间新任务永远排不上（启动预热卡死根因）。
        if (submitted > 0 && !this.pendingGenerationTasks.isEmpty()) {
            this.mainThreadExecutor.execute(() -> ((ChunkMap) (Object) this).runGenerationTasks());
        }
    }

    /** 采样 30s 滑动窗口的 GC 时间占比（GarbageCollectorMXBean collectionTime）。 */
    @Unique
    private static void prts$sampleGcPressure() {
        long now = System.nanoTime();
        if (prts$lastGcSampleNanos != 0L && now - prts$lastGcSampleNanos < 30_000_000_000L) {
            return;
        }
        long gcMs = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcMs += bean.getCollectionTime();
        }
        long elapsedMs = (now - prts$lastGcSampleNanos) / 1_000_000L;
        if (prts$lastGcSampleNanos != 0L && elapsedMs > 0L) {
            prts$gcRatio = (double) (gcMs - prts$lastGcTotalMs) / elapsedMs;
        }
        prts$lastGcSampleNanos = now;
        prts$lastGcTotalMs = gcMs;
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
