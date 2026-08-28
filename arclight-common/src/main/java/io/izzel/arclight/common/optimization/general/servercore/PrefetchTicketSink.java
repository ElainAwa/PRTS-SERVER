/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 预铺票投递口（v03 依赖根预铺载体）：以自定义票级投递浅需求票。
 * 票级 = {@code ChunkLevel.byStatus(STRUCTURE_STARTS)}（41），只建 holder 并把
 * 块目标定在 structure_starts，绝不推进 FULL；玩家接近后 FULL 票覆盖自然升级。
 * mixin 实现（{@code ServerChunkCacheMixin_PrefetchTicket}）。
 */
public interface PrefetchTicketSink {

    <T> void prts$addPrefetchTicket(TicketType<T> type, ChunkPos pos, int level, T key);
}
