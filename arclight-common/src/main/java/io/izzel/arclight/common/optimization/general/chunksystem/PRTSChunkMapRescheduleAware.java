/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * M2 状态机对 {@link net.minecraft.server.level.ChunkMap} 的重调度延迟投递面（mixin 实现）。
 *
 * <p>背景：任务图的依赖门控跑在 worker 线程，请求邻居 future 时若邻居没有足够目标
 * 的驱动任务，原版语义会经 {@code scheduleChunkGenerationTask} 的 reschedule 分支新建
 * 生成任务进 {@code pendingGenerationTasks}。但该链非线程安全（ArrayList 的
 * {@code pendingGenerationTasks} 与 {@code updatingChunkMap} 均为维度事件循环线程独占），
 * 且 M2 的 future 物化走的是私有 {@code getOrCreateFuture}（不含 reschedule）——突发风暴
 * 下邻居块因此永无驱动者，门控 future 永不结算（2026-08-25 死锁根因）。
 *
 * <p>方案：worker 线程把请求入队，由维度事件循环线程在 {@code runGenerationTasks()}
 * 冲刷前统一消化（调用原版公开 {@code scheduleChunkGenerationTask}，与原版主线程语义逐位一致）。
 */
public interface PRTSChunkMapRescheduleAware {

    /** 延迟投递一次 (holder, status) 的重调度请求；可在任意线程调用。 */
    void prts$deferReschedule(GenerationChunkHolder holder, ChunkStatus status);
}
