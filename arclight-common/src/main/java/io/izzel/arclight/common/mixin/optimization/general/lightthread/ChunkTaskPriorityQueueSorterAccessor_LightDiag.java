/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.lightthread;

import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;

/**
 * 光链诊断访问面：读 {@code ChunkTaskPriorityQueueSorter} 私有状态（队列映射表 / sleeping 集合）。
 * {@code mailbox} 字段注解处理器无法解析，改由 {@link LightChainDiag} 运行期反射读取。
 */
@Mixin(ChunkTaskPriorityQueueSorter.class)
public interface ChunkTaskPriorityQueueSorterAccessor_LightDiag {

    @Accessor("queues")
    Map<ProcessorHandle<?>, Object> prts$queues();

    @Accessor("sleeping")
    Set<ProcessorHandle<?>> prts$sleeping();
}
