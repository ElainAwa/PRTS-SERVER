/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 两阶段锁域拆分（阶段二）的任务状态接口，由
 * {@code ChunkGenerationTaskMixin_LockSplit} 以 @Unique 字段实现，
 * 调度器经 {@code instanceof} 访问。标记该生成任务是否已完成
 * 「中心块锁 → 5×5 锁」的一次性升级，防止重复暂停。
 */
public interface PRTSLockSplitAware {

    boolean prts$isUpgraded();

    void prts$setUpgraded(boolean upgraded);

    /** 当前已调度到的状态层（依赖等待遥测按层归因用）。 */
    @Nullable
    ChunkStatus prts$getScheduledStatus();

    /**
     * 依赖门控（阶段四）：收集当前已调度层中尚未完成的 future。
     * 仅在任务独占的同步段内（{@code runUntilWait} 刚返回后）调用。
     * 默认空列表（mixin 未生效时调度器退回原版尾 future 唤醒）。
     */
    default List<CompletableFuture<?>> prts$collectPendingLayerFutures() {
        return List.of();
    }
}
