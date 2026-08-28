/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 预铺投递口（v03 依赖根预铺载体）：两步投递。
 * <ol>
 *   <li>{@link #prts$addPrefetchTicket}：STRUCTURE_STARTS 级票（level =
 *   {@code ChunkLevel.byStatus(STRUCTURE_STARTS)} = 41）建 holder 并保持加载；
 *   玩家接近后 FULL 票覆盖，既有升级路径无缝接管。</li>
 *   <li>{@link #prts$scheduleRootTask}：原版 {@code ChunkMap.scheduleGenerationTask}
 *   投 STRUCTURE_STARTS 目标任务。票只建 holder 不会自动建任务（原版任务创建仅
 *   在 fullStatus≥FULL 的 promotion 路径），必须显式投任务；且须在票生效后
 *   （holder 已存在）调用，否则 {@code acquireGeneration} 对缺失 holder NPE。</li>
 * </ol>
 * mixin 实现（{@code ServerChunkCacheMixin_PrefetchTicket}）。
 */
public interface PrefetchTicketSink {

    <T> void prts$addPrefetchTicket(TicketType<T> type, ChunkPos pos, int level, T key);

    void prts$scheduleRootTask(ChunkPos pos);
}
