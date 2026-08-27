/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.lightthread;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 光链诊断访问面：读 {@code ChunkMap} 私有 {@code queueSorter}。
 * {@code updatingChunkMap} 字段注解处理器无法解析，改由 {@link LightChainDiag} 运行期反射读取。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor_LightDiag {

    @Accessor("queueSorter")
    ChunkTaskPriorityQueueSorter prts$queueSorter();
}
