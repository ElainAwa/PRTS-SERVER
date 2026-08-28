/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 预铺票投递口：以指定票级投递（addRegionTicket 只能投 FULL 级）。
 */
public interface PrefetchTicketSink {

    <T> void prts$addPrefetchTicket(TicketType<T> type, ChunkPos pos, int level, T key);
}
