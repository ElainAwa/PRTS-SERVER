/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemScheduler;
import io.izzel.arclight.common.optimization.general.chunksystem.PRTSLockSplitAware;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 两阶段锁域拆分（阶段二，配置灰度）：任务以中心块锁跑完 features 前步骤
 * （原版声明这些步骤写半径均为 0，邻块读由 future 依赖链保序——读依赖审计
 * 基于 ChunkPyramid.GENERATION_PYRAMID 声明），进入 FEATURES 层前在
 * {@code scheduleNextLayer} 注入一次性暂停：{@code runUntilWait} 返回哨兵
 * future，调度器识别后释放中心块锁、以 5×5 锁重新入队续跑。
 *
 * <p>仅在调度器窄锁段执行时（{@code ChunkSystemScheduler.isNarrowLockActive()}）
 * 生效；原版邮箱路径与关闭配置下零影响。暂停点语义安全：此时所有已调度层
 * 的 future 均已完成（{@code waitForScheduledLayer} 返回空才走到调度下一层），
 * 无部分调度状态；续跑时 {@code scheduleNextLayer} 幂等（同一层重新调度）。
 */
@Mixin(ChunkGenerationTask.class)
public abstract class ChunkGenerationTaskMixin_LockSplit implements PRTSLockSplitAware {

    @Shadow
    @Nullable
    private ChunkStatus scheduledStatus;

    @Shadow
    private List<CompletableFuture<ChunkResult<ChunkAccess>>> scheduledLayer;

    @Unique
    private boolean prts$pauseRequested;

    @Unique
    private boolean prts$upgraded;

    @Override
    public boolean prts$isUpgraded() {
        return this.prts$upgraded;
    }

    @Override
    public void prts$setUpgraded(boolean upgraded) {
        this.prts$upgraded = upgraded;
    }

    @Override
    public ChunkStatus prts$getScheduledStatus() {
        return this.scheduledStatus;
    }

    /**
     * 依赖门控（阶段四）：收集当前层未完成的 future。
     * {@code waitForScheduledLayer} 只看尾部第一个未完成 future，尾完成即唤醒续段，
     * 若层内其它 future 仍未完成会立即再挂起（层内空转往返）；调度器改用本列表
     * 全完成后才入队，消除空转。仅在任务独占的同步段内调用，无并发读写。
     */
    @Override
    public List<CompletableFuture<?>> prts$collectPendingLayerFutures() {
        List<CompletableFuture<?>> pending = new ArrayList<>(this.scheduledLayer.size());
        for (CompletableFuture<ChunkResult<ChunkAccess>> future : this.scheduledLayer) {
            if (future.getNow(null) == null) {
                pending.add(future);
            }
        }
        return pending;
    }

    /** 下一层是 FEATURES 且当前持窄锁 → 取消本层调度并置暂停标记。 */
    @Inject(method = "scheduleNextLayer", at = @At("HEAD"), cancellable = true)
    private void prts$pauseBeforeFeatures(CallbackInfo ci) {
        if (!ChunkSystemScheduler.isNarrowLockActive() || this.prts$upgraded) {
            return;
        }
        ChunkStatus next = this.scheduledStatus == null
                ? ChunkStatus.EMPTY
                : ChunkStatus.getStatusList().get(this.scheduledStatus.getIndex() + 1);
        if (next == ChunkStatus.FEATURES) {
            this.prts$pauseRequested = true;
            ci.cancel();
        }
    }

    /** 消费暂停标记：返回哨兵 future 让 runUntilWait 结束本同步段。 */
    @Inject(method = "waitForScheduledLayer", at = @At("HEAD"), cancellable = true)
    private void prts$returnPauseToken(CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (this.prts$pauseRequested) {
            this.prts$pauseRequested = false;
            cir.setReturnValue(ChunkSystemScheduler.lockUpgradePause());
        }
    }
}
