/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家方向区块预取:按移动方向对视距外区块投临时 ticket,使生成提前完成;
 * ticket 到期自动回收,全流程 try/catch 纯增益。
 */
public final class ChunkPrefetcher {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /** 预取 ticket 类型（懒创建：配置在 mod 加载时先于世界生成）。 */
    private static volatile TicketType<Unit> prefetchTicket;

    /** 每玩家轻量状态（上次位置/所在 chunk/上次重算游戏刻）。 */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** 状态表容量兜底：超出整体清空（丢失仅损失一次方向估计，无害）。 */
    private static final int STATE_CAPACITY = 512;

    private ChunkPrefetcher() {
    }

    /** 每次玩家 tick 调用一次（主线程）。 */
    public static void onPlayerTick(ServerPlayer player) {
        if (!PRTSFeaturesConfig.chunkPrefetchEnabled) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            // 预取是纯增益：任何异常都不应影响玩家 tick。
        }
    }

    private static TicketType<Unit> ticket() {
        TicketType<Unit> t = prefetchTicket;
        if (t == null) {
            synchronized (ChunkPrefetcher.class) {
                t = prefetchTicket;
                if (t == null) {
                    t = TicketType.create("prts_prefetch", (a, b) -> 0,
                            Math.max(20, PRTSFeaturesConfig.chunkPrefetchTimeoutTicks));
                    prefetchTicket = t;
                }
            }
        }
        return t;
    }

    private static void tick(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ServerChunkCache source = level.getChunkSource();

        State state = STATES.computeIfAbsent(player.getUUID(), u -> new State());
        if (STATES.size() > STATE_CAPACITY) {
            STATES.clear();
        }

        if (!state.initialized) {
            state.init(player, level.getGameTime());
            return;
        }

        long gameTime = level.getGameTime();
        if (gameTime - state.lastGameTime < PRTSFeaturesConfig.chunkPrefetchIntervalTicks) {
            return;
        }
        state.lastGameTime = gameTime;

        double x = player.getX();
        double z = player.getZ();
        double dx = x - state.lastX;
        double dz = z - state.lastZ;
        state.lastX = x;
        state.lastZ = z;

        if (dx * dx + dz * dz < 1.0) {
            return; // 原地不动，不预取
        }

        ChunkPos chunk = player.chunkPosition();
        int dirX = Integer.compare(chunk.x, state.lastChunkX);
        int dirZ = Integer.compare(chunk.z, state.lastChunkZ);
        state.lastChunkX = chunk.x;
        state.lastChunkZ = chunk.z;

        if (dirX == 0 && dirZ == 0) {
            return; // 尚未跨 chunk，方向不可定
        }

        int viewDist = level.getServer().getPlayerList().getViewDistance();
        int from = viewDist + 1;
        int to = viewDist + PRTSFeaturesConfig.chunkPrefetchDepth;
        int prefetched = 0;
        for (int k = from; k <= to; k++) {
            if (prefetch(source, chunk.x + dirX * k, chunk.z + dirZ * k)) {
                prefetched++;
            }
        }
        if (state.firstLog || gameTime - state.lastLogGameTime >= 20) {
            state.firstLog = false;
            state.lastLogGameTime = gameTime;
            LOGGER.info("[chunk-prefetch] player {} direction ({},{}) view={} window [{}~{}] tickets={}",
                    player.getGameProfile().getName(), dirX, dirZ, viewDist, from, to, prefetched);
        }
    }

    private static boolean prefetch(ServerChunkCache source, int chunkX, int chunkZ) {
        if (source.hasChunk(chunkX, chunkZ)) {
            return false; // 已 FULL，无需预取
        }
        // radius=0 → FULL 级 ticket；到期自动回收，不阻塞、不泄漏。
        source.addRegionTicket(ticket(), new ChunkPos(chunkX, chunkZ), 0, Unit.INSTANCE);
        return true;
    }

    private static final class State {
        boolean initialized;
        double lastX;
        double lastZ;
        int lastChunkX;
        int lastChunkZ;
        long lastGameTime;
        long lastLogGameTime;
        boolean firstLog;

        void init(ServerPlayer player, long gameTime) {
            this.initialized = true;
            this.lastX = player.getX();
            this.lastZ = player.getZ();
            ChunkPos c = player.chunkPosition();
            this.lastChunkX = c.x;
            this.lastChunkZ = c.z;
            this.lastGameTime = gameTime;
            this.firstLog = true;
        }
    }
}
