/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemDriver;
import io.izzel.arclight.common.optimization.general.chunksystem.ChunkSystemScheduler;
import io.izzel.arclight.common.optimization.general.chunksystem.PRTSChunkMapRescheduleAware;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 区块系统调度器接入：把 {@code runGenerationTask} 的 worldgen 邮箱提交改经
 * PRTS 调度层。两条路径（配置互斥，启动期生效）：
 * <ul>
 *   <li>{@code chunk-system-enabled}（M2.1）：整任务分解为单区块×单状态细粒度
 *   任务图（{@link ChunkSystemDriver}）；</li>
 *   <li>{@code chunk-system-scheduler.enabled}（M1）：整任务进优先级队列，
 *   状态推进仍由原版 future 链完成。</li>
 * </ul>
 * 两者都关时走原版路径逐位一致。
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_ChunkSystemSched implements PRTSChunkMapRescheduleAware {

    @Shadow
    @Final
    ServerLevel level;

    /**
     * M2 门控（worker 线程）投递的延迟重调度请求，在 {@code runGenerationTasks()}
     * 冲刷前由维度事件循环线程统一消化（原版主线程语义，见接口注释）。
     */
    @Unique
    private final ConcurrentLinkedQueue<Runnable> prts$deferredReschedules = new ConcurrentLinkedQueue<>();

    @Override
    public void prts$deferReschedule(GenerationChunkHolder holder, ChunkStatus status) {
        ChunkMap self = (ChunkMap) (Object) this;
        this.prts$deferredReschedules.add(() -> holder.scheduleChunkGenerationTask(status, self));
    }

    @Override
    public void prts$drainDeferredReschedules() {
        if (!PRTSFeaturesConfig.chunkSystemEnabled) {
            return;
        }
        Runnable request;
        while ((request = this.prts$deferredReschedules.poll()) != null) {
            request.run();
        }
    }

    @Inject(method = "runGenerationTasks", at = @At("HEAD"))
    private void prts$drainDeferredReschedulesTick(CallbackInfo ci) {
        this.prts$drainDeferredReschedules();
    }

    @Inject(method = "runGenerationTask", at = @At("HEAD"), cancellable = true)
    private void prts$scheduleViaChunkSystem(ChunkGenerationTask task, CallbackInfo ci) {
        if (PRTSFeaturesConfig.chunkSystemEnabled) {
            try {
                ChunkSystemDriver.submit(this.level, (ChunkMap) (Object) this, task);
            } catch (Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkSystem")
                        .error("[chunk-system] M2 submit failed for {} target={}", task.getCenter().getPos(), task.targetStatus, t);
            }
            ci.cancel();
            return;
        }
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemScheduler.submit(this.level, task);
        ci.cancel();
    }
}
