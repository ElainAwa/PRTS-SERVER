/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.chunk_prefetch;

import io.izzel.arclight.common.optimization.general.servercore.PrefetchTicketSink;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 预铺投递口实现：
 * <ul>
 *   <li>{@code DistanceManager.addTicket(TicketType, ChunkPos, int, T)} 是 public 且
 *   直接携带票级，可投 STRUCTURE_STARTS 级（41）浅需求票；与 region 票不同，
 *   不注册 tickingTicketsTracker（预铺块不需要 block ticking）。</li>
 *   <li>{@code ChunkMap.scheduleGenerationTask} 显式投 STRUCTURE_STARTS 目标任务
 *   （原版对 34-41 级 holder 不会自动建任务），走 M2 预铺优先级档执行。</li>
 * </ul>
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_PrefetchTicket implements PrefetchTicketSink {

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    @Final
    public ChunkMap chunkMap;

    @Override
    public <T> void prts$addPrefetchTicket(TicketType<T> type, ChunkPos pos, int level, T key) {
        this.distanceManager.addTicket(type, pos, level, key);
    }

    @Override
    public void prts$scheduleRootTask(ChunkPos pos) {
        this.chunkMap.scheduleGenerationTask(ChunkStatus.STRUCTURE_STARTS, pos);
    }
}
