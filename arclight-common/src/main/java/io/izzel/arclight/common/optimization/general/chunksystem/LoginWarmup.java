/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.PrefetchTicketSink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * 进服热点预热：服务器启动后逐 tick 给配置坐标区域预铺 FULL 票，
 * 玩家首次进服时区块已在内存，避免重启后首登的磁盘读+反序列化等待。
 * 仅主线程调用（schedule 首次 tick、tick 每 tick），0 玩家同样生效。
 */
public final class LoginWarmup {

    private static boolean scheduled = false;
    private static final Queue<ChunkPos> PENDING = new ArrayDeque<>();

    /** 预热票：1 小时 TTL，热点在重启后首登窗口保持加载，过期自动释放。 */
    private static final net.minecraft.server.level.TicketType<com.mojang.datafixers.util.Unit> TICKET =
            net.minecraft.server.level.TicketType.create("prts_login_warmup", (a, b) -> 0, 72000);

    private LoginWarmup() {
    }

    /** 首次 tick 调用：按配置中心+半径铺开预热队列。 */
    public static void schedule(MinecraftServer server) {
        if (scheduled || !PRTSFeaturesConfig.loginWarmupEnabled) {
            return;
        }
        scheduled = true;
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        int radius = Math.max(0, PRTSFeaturesConfig.loginWarmupRadius);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.List<int[]> centers = new java.util.ArrayList<>(
                java.util.Arrays.asList(PRTSFeaturesConfig.loginWarmupCenters));
        // 世界出生点也预热：进服点可能不在配置中心（玩家重生/首登）。
        net.minecraft.core.BlockPos spawn = overworld.getSharedSpawnPos();
        centers.add(new int[]{spawn.getX(), spawn.getZ()});
        for (int[] center : centers) {
            int cx = Math.floorDiv(center[0], 16);
            int cz = Math.floorDiv(center[1], 16);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (seen.add(ChunkPos.asLong(cx + dx, cz + dz))) {
                        PENDING.add(new ChunkPos(cx + dx, cz + dz));
                    }
                }
            }
        }
    }

    /** 每 tick 调用：按预算预铺 FULL 票。非阻塞：holder/生成任务由距离管理器
     *  与生成预算异步推进；阻塞式 getChunk 在生成节流下会等任务提交而卡死主线程。 */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }
        int budget = Math.max(1, PRTSFeaturesConfig.loginWarmupPerTick);
        while (budget-- > 0 && !PENDING.isEmpty()) {
            ChunkPos pos = PENDING.poll();
            try {
                ((PrefetchTicketSink) overworld.getChunkSource())
                        .prts$addPrefetchTicket(TICKET, pos,
                                ChunkLevel.byStatus(ChunkStatus.FULL),
                                com.mojang.datafixers.util.Unit.INSTANCE);
            } catch (Throwable ignored) {
                // 预热只是优化；失败时玩家进服路径仍会按需加载。
            }
        }
    }
}
