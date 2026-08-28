/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.chunk_prefetch;

import io.izzel.arclight.common.optimization.general.servercore.PrefetchTicketSink;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 预铺票投递口：DistanceManager.addTicket 直接携带票级。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_PrefetchTicket implements PrefetchTicketSink {

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Override
    public <T> void prts$addPrefetchTicket(TicketType<T> type, ChunkPos pos, int level, T key) {
        this.distanceManager.addTicket(type, pos, level, key);
    }
}
