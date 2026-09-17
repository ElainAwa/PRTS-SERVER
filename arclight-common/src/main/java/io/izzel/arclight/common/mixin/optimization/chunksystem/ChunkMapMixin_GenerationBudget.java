/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.chunksystem.MainThreadChunkWaits;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
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

    @Shadow
    @Final
    private ServerLevel level;

    /** 放行日志节流（10s 一条），避免风暴期刷屏。 */
    @Unique
    private static long prts$awaitedLogNanos = 0L;

    /** 主线程事件循环：managedBlock 会泵它，阻塞 getChunk 期间也能推进提交。 */
    @Shadow
    @Final
    private net.minecraft.util.thread.BlockableEventLoop<Runnable> mainThreadExecutor;

    @Inject(method = "runGenerationTasks", at = @At("HEAD"), cancellable = true)
    private void arclight$budgetedRunGenerationTasks(CallbackInfo ci) {
        // 最高优先级：主线程此刻正阻塞等待的区块，无论预算/窗口/内存卫兵如何都要先提交，
        // 否则它的生成任务可能永远排不上（提交窗口被其它路径占满时即僵死）。
        if (prts$submitAwaited()) {
            if (this.pendingGenerationTasks.isEmpty()) {
                return; // 已全部提交：放行原版路径（列表为空，等价于空转）
            }
        }
        int budget = PRTSFeaturesConfig.generationTasksPerTick;
        int limit = PRTSFeaturesConfig.chunkgenInflightLimit;
        if (budget <= 0 && limit <= 0) {
            return;
        }
        if (PRTSFeaturesConfig.generationMemoryGuardEnabled) {
            Runtime runtime = Runtime.getRuntime();
            double committedRatio = (double) runtime.totalMemory() / runtime.maxMemory();
            double usedRatio = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
            // Xms==Xmx 时 committed 恒为 1，必须改用 used 判定；否则按两者较大值
            double ratio = committedRatio >= 1.0 ? usedRatio : Math.max(committedRatio, usedRatio);
            prts$sampleGcPressure();
            boolean gcPressure = prts$gcRatio > 0.30 && ratio > 0.55;
            if (gcPressure && prts$guardLogCooldownNanos == 0L
                    || gcPressure && System.nanoTime() - prts$guardLogCooldownNanos > 30_000_000_000L) {
                prts$guardLogCooldownNanos = System.nanoTime();
                org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkGen")
                        .warn("[chunk-gen] memory guard GC pressure (gc={}% used={}% committed={}%) throttling early",
                                (int) (prts$gcRatio * 100), (int) (usedRatio * 100), (int) (committedRatio * 100));
            }
            if (ratio >= PRTSFeaturesConfig.generationMemoryGuardPauseRatio) {
                // 暂停提交：等 GC 追回 used 后再恢复，防加载风暴把堆顶满 Xmx
                if (prts$guardLogCooldownNanos == 0L
                        || System.nanoTime() - prts$guardLogCooldownNanos > 10_000_000_000L) {
                    prts$guardLogCooldownNanos = System.nanoTime();
                    org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkGen")
                            .warn("[chunk-gen] memory guard PAUSE submissions (used={}% committed={}%)",
                                    (int) (usedRatio * 100), (int) (committedRatio * 100));
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

    /**
     * 提交主线程正在同步等待的区块对应的生成任务：绕过每 tick 预算、滚动窗口与内存卫兵。
     *
     * <p>只放行"主线程已经在等"的那一个/几个区块，其余仍按预算走，削峰语义不变；
     * 放行粒度到"进入生成管线"为止，之后的依赖/步骤由管线自身推进（不再受提交预算约束）。
     * 无等待者时零分配快速返回（本方法在阻塞期间的 pollTask 上每 ~100µs 被调用一次）。
     *
     * @return 是否提交了至少一个任务
     */
    @Unique
    private boolean prts$submitAwaited() {
        if (this.pendingGenerationTasks.isEmpty()) {
            return false;
        }
        java.util.Collection<MainThreadChunkWaits.Wait> waits = MainThreadChunkWaits.waits();
        if (waits.isEmpty()) {
            return false;
        }
        boolean submitted = false;
        int bypassed = 0;
        long now = System.nanoTime();
        for (MainThreadChunkWaits.Wait wait : waits) {
            if (bypassed >= 8 || this.pendingGenerationTasks.isEmpty()) {
                break;
            }
            // 不是本维度：无需再扫。
            // 注意：不能记"已放行过"就永久跳过 —— 同一区块在等待期间还可能有第二个
            // 任务（实测先 initialize_light 后 full），跳过就回到"主线程等一个永远
            // 排不上队的任务"的老僵死（曾把启动卡在 Preparing spawn area）。
            if (!wait.dimension().equals(this.level.dimension())) {
                continue;
            }
            // 扫描是 O(待提交任务数)，而本方法在阻塞期间每 ~100µs 被调一次 →
            // 必须限流，否则主线程把 CPU 全烧在全量扫描上（实测 1.3 万条待提交时
            // 主线程 100% 占用、worldgen worker 反而饿着）。
            if (now - wait.lastScanNanos < MainThreadChunkWaits.SCAN_INTERVAL_NANOS) {
                continue;
            }
            wait.lastScanNanos = now;
            Iterator<ChunkGenerationTask> it = this.pendingGenerationTasks.iterator();
            while (it.hasNext()) {
                ChunkGenerationTask task = it.next();
                net.minecraft.world.level.ChunkPos pos = task.getCenter().getPos();
                if (pos.x != wait.x() || pos.z != wait.z()) {
                    continue;
                }
                this.arclight$runGenerationTask(task);
                it.remove();
                prts$submitTimes[prts$submitIndex++ % prts$submitTimes.length] = System.nanoTime();
                bypassed++;
                submitted = true;
                wait.lastScanNanos = now;
                if (now - prts$awaitedLogNanos > 10_000_000_000L) {
                    prts$awaitedLogNanos = now;
                    org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkGen")
                            .warn("[chunk-gen] submit awaited chunk {} target={} bypassing budget (pending={})",
                                    pos, task.targetStatus, this.pendingGenerationTasks.size());
                }
                break;
            }
        }
        return submitted;
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
