/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.lightthread;

import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 光链诊断访问面：读 {@code ChunkTaskPriorityQueue} 私有积压状态。
 */
@Mixin(ChunkTaskPriorityQueue.class)
public interface ChunkTaskPriorityQueueAccessor_LightDiag {

    @Accessor("taskQueue")
    List<Object> prts$taskQueue();

    @Accessor("firstQueue")
    int prts$firstQueue();
}
