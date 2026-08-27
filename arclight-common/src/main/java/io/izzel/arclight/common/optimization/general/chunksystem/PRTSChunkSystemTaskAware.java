/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.util.StaticCache2D;
import net.minecraft.server.level.GenerationChunkHolder;

/**
 * M2.1 状态机驱动器对 {@link ChunkGenerationTask} 的访问面（mixin 实现）。
 * 驱动器复用原任务 {@code create()} 时已获取的锥域持有（cache + generation
 * refCount），不重新 acquire（{@code ChunkMap.acquireGeneration} 对缺失 holder
 * 会 NPE）；任务图全部结算后经 {@link #prts$releaseClaim()} 归还原版语义的释放。
 */
public interface PRTSChunkSystemTaskAware {

    /** 锥域持有缓存（{@code StaticCache2D.create} 时已对全域 acquireGeneration）。 */
    StaticCache2D<GenerationChunkHolder> prts$cache();

    /** 原版 {@code releaseClaim}：中心块移除任务引用 + 全域释放 generation refCount。 */
    void prts$releaseClaim();

    /** 取消标记（{@code rescheduleChunkTask} 换任务时置位）。 */
    boolean prts$isMarkedForCancellation();
}
