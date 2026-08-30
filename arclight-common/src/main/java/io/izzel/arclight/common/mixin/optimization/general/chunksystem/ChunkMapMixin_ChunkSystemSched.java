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

/** 把区块生成任务按配置路由到细粒度状态机或优先级调度器。 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin_ChunkSystemSched implements PRTSChunkMapRescheduleAware {

    @Shadow
    @Final
    ServerLevel level;

    /** 延迟重调度队列，由维度事件循环线程消化。 */
    @Unique
    private final ConcurrentLinkedQueue<Runnable> prts$deferredReschedules = new ConcurrentLinkedQueue<>();

    @Override
    public void prts$deferReschedule(GenerationChunkHolder holder, ChunkStatus status) {
        ChunkMap self = (ChunkMap) (Object) this;
        this.prts$deferredReschedules.add(() -> {
            try {
                holder.scheduleChunkGenerationTask(status, self);
            } finally {
                // 消费即清去重位，避免 SHARED_DEFERRED 无界增长/永久抑制后续重调度
                ChunkSystemDriver.deferredDrained(this.level.dimension(),
                        holder.getPos().x, holder.getPos().z, status.getIndex());
            }
        });
    }

    @Override
    public void prts$drainDeferredReschedules() {
        if (!PRTSFeaturesConfig.chunkSystemEnabled) {
            return;
        }
        Runnable request;
        while ((request = this.prts$deferredReschedules.poll()) != null) {
            try {
                request.run();
            } catch (Throwable t) {
                // 单条失败不得打断 runGenerationTasks 的整个 tick 驱动链
                org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkSystem")
                        .warn("[chunk-system] deferred reschedule failed", t);
            }
        }
    }

    @Inject(method = "runGenerationTasks", at = @At("HEAD"))
    private void prts$drainDeferredReschedulesTick(CallbackInfo ci) {
        this.prts$drainDeferredReschedules();
    }

    @Inject(method = "runGenerationTask", at = @At("HEAD"), cancellable = true)
    private void prts$scheduleViaChunkSystem(ChunkGenerationTask task, CallbackInfo ci) {
        if (PRTSFeaturesConfig.chunkSystemEnabled) {
            boolean submitted = false;
            try {
                ChunkSystemDriver.submit(this.level, (ChunkMap) (Object) this, task);
                submitted = true;
            } catch (Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger("PRTS-ChunkSystem")
                        .error("[chunk-system] submit failed for {} target={}", task.getCenter().getPos(), task.targetStatus, t);
            }
            if (submitted) {
                ci.cancel();
            }
            // submit 失败时放行 vanilla 路径，避免生成请求静默丢失
            return;
        }
        if (!PRTSFeaturesConfig.chunkSystemSchedulerEnabled) {
            return;
        }
        ChunkSystemScheduler.submit(this.level, task);
        ci.cancel();
    }
}
