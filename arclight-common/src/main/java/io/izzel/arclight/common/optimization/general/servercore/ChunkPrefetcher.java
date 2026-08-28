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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 玩家方向区块预铺：对前方窗口投 FULL 级票，让生成链在玩家到达前跑完；
 * 静止时在视距外环预铺。窗口重算节流、上限补货、最近优先，主线程执行。
 */
public final class ChunkPrefetcher {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    /** 预铺票类型（懒创建：配置在 mod 加载时先于世界生成）。 */
    private static volatile TicketType<Unit> prefetchTicket;

    /** 每玩家轻量状态（上次位置/方向/窗口重算节流/idle 滞回）。 */
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** 状态表容量兜底：超出整体清空（丢失仅损失一次方向估计，无害）。 */
    private static final int STATE_CAPACITY = 512;

    // ===== 遥测（servercore status 可见） =====
    private static final LongAdder TICKETS = new LongAdder();
    private static final LongAdder SKIPPED_FULL = new LongAdder();
    private static final LongAdder RECOMPUTES = new LongAdder();
    private static final LongAdder IDLE_ENTERS = new LongAdder();
    private static final LongAdder IDLE_EXITS = new LongAdder();

    private ChunkPrefetcher() {
    }

    /** idle 退出后的重入宽限（tick）：防止块边界悬停时 idle/窗口来回抖振。 */
    private static final long IDLE_REENTER_GRACE_TICKS = 40;

    /** 每次玩家 tick 调用一次（主线程）。 */
    public static void onPlayerTick(ServerPlayer player) {
        if (!PRTSFeaturesConfig.chunkPrefetchEnabled) {
            return;
        }
        try {
            tick(player);
        } catch (Throwable t) {
            // 预铺是纯增益：任何异常都不应影响玩家 tick。
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
        int elapsed = (int) (gameTime - state.lastGameTime);
        if (elapsed < PRTSFeaturesConfig.chunkPrefetchIntervalTicks) {
            return;
        }
        state.lastGameTime = gameTime;

        ChunkPos chunk = player.chunkPosition();
        int dx = chunk.x - state.lastChunkX;
        int dz = chunk.z - state.lastChunkZ;
        state.lastChunkX = chunk.x;
        state.lastChunkZ = chunk.z;
        int moved = Math.max(Math.abs(dx), Math.abs(dz));

        if (moved > 0) {
            state.dirX = Integer.compare(dx, 0);
            state.dirZ = Integer.compare(dz, 0);
        }

        // idle：100 tick 总位移 <2 块进入；任意跨块退出（40 tick 宽限防抖振）
        if (PRTSFeaturesConfig.chunkPrefetchIdleEnabled) {
            if (!state.idle) {
                state.idleWindowTicks += elapsed;
                state.idleDistance += moved;
                if (state.idleWindowTicks >= PRTSFeaturesConfig.chunkPrefetchIdleEnterTicks) {
                    if (state.idleDistance < 2
                            && gameTime - state.lastIdleExitGameTime >= IDLE_REENTER_GRACE_TICKS) {
                        state.idle = true;
                        IDLE_ENTERS.increment();
                        LOGGER.info("[chunk-prefetch] {} entered idle pre-generation (radius={}, perTick={})",
                                player.getGameProfile().getName(),
                                PRTSFeaturesConfig.chunkPrefetchIdleRadius,
                                PRTSFeaturesConfig.chunkPrefetchIdlePerTick);
                    }
                    state.idleWindowTicks = 0;
                    state.idleDistance = 0;
                }
            } else if (moved >= 1) {
                // 任意跨块立即退出 idle（窗口严格优于环形；重入有 40 tick 宽限防块边界抖振）
                state.idle = false;
                state.windowDirty = true;
                state.idleWindowTicks = 0;
                state.idleDistance = 0;
                state.lastIdleExitGameTime = gameTime;
                IDLE_EXITS.increment();
                LOGGER.info("[chunk-prefetch] {} exited idle (crossed chunk, moved {}), back to directional window",
                        player.getGameProfile().getName(), moved);
            }
        }

        // 窗口重算触发：脏标记 / 超时 / （非 idle 且）跨块达阈值
        boolean recompute = state.windowDirty
                || gameTime - state.lastRecomputeGameTime >= PRTSFeaturesConfig.chunkPrefetchWindowRecomputeTicks
                || (!state.idle && moved >= PRTSFeaturesConfig.chunkPrefetchWindowStep);
        if (recompute && !state.idle && state.dirX == 0 && state.dirZ == 0) {
            recompute = false; // 方向未定（idle 关闭且从未跨块）：方向性窗口无法计算
        }
        if (!recompute) {
            return;
        }
        int sinceRecompute = (int) (gameTime - state.lastRecomputeGameTime);
        state.windowDirty = false;
        state.lastRecomputeGameTime = gameTime;
        RECOMPUTES.increment();
        recomputeWindow(player, level, source, state, sinceRecompute);
    }

    /** 全量重算窗口并补货（最近优先、上限约束、idle 平滑限速）。 */
    private static void recomputeWindow(ServerPlayer player, ServerLevel level, ServerChunkCache source,
                                        State state, int sinceRecompute) {
        ChunkPos chunk = player.chunkPosition();
        int viewDist = level.getServer().getPlayerList().getViewDistance();
        List<ChunkPos> candidates = new ArrayList<>();
        int budget;
        if (state.idle) {
            // idle：视距外环 [viewDist+1, viewDist+idleRadius]，逐环外扩
            int from = viewDist + 1;
            int to = viewDist + PRTSFeaturesConfig.chunkPrefetchIdleRadius;
            for (int r = from; r <= to; r++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != r) {
                            continue;
                        }
                        candidates.add(new ChunkPos(chunk.x + x, chunk.z + z));
                    }
                }
            }
            // 平滑限速：预算 = perTick × 距上次重算 tick 数（上限仍受 max-pending 约束）
            budget = PRTSFeaturesConfig.chunkPrefetchIdlePerTick * Math.max(1, sinceRecompute);
        } else {
            // 方向性窗口：前方 window×window 矩形（深度 [viewDist+1, viewDist+window]）
            int from = viewDist + 1;
            int to = viewDist + PRTSFeaturesConfig.chunkPrefetchWindow;
            int half = PRTSFeaturesConfig.chunkPrefetchWindow / 2;
            if (state.dirX != 0 && state.dirZ != 0) {
                for (int k = from; k <= to; k++) {
                    for (int j = from; j <= to; j++) {
                        candidates.add(new ChunkPos(chunk.x + state.dirX * k, chunk.z + state.dirZ * j));
                    }
                }
            } else if (state.dirX != 0) {
                for (int k = from; k <= to; k++) {
                    for (int w = -half; w <= half; w++) {
                        candidates.add(new ChunkPos(chunk.x + state.dirX * k, chunk.z + w));
                    }
                }
            } else {
                for (int w = -half; w <= half; w++) {
                    for (int k = from; k <= to; k++) {
                        candidates.add(new ChunkPos(chunk.x + w, chunk.z + state.dirZ * k));
                    }
                }
            }
            budget = Integer.MAX_VALUE;
        }

        candidates.sort(Comparator.comparingInt(c -> Math.max(
                Math.abs(c.x - chunk.x), Math.abs(c.z - chunk.z))));

        int maxPending = PRTSFeaturesConfig.chunkPrefetchMaxPending;
        Set<Long> next = new HashSet<>(Math.min(candidates.size(), maxPending) + 8);
        int tickets = 0;
        for (ChunkPos pos : candidates) {
            if (next.size() >= maxPending) {
                break; // 上限：防队列膨胀
            }
            if (budget != Integer.MAX_VALUE && tickets >= budget) {
                break; // idle 平滑限速
            }
            if (source.hasChunk(pos.x, pos.z)) {
                SKIPPED_FULL.increment();
                continue; // 已 FULL，无需预铺
            }
            if (!next.add(pos.toLong())) {
                continue; // 本窗口已选
            }
            if (!state.pending.contains(pos.toLong())) {
                // 新块投 FULL 级票（level 33）：原版自动建生成任务，重复投幂等
                source.addRegionTicket(ticket(), pos, 0, Unit.INSTANCE);
                tickets++;
                TICKETS.increment();
            }
        }
        state.pending = next;

        if (state.firstLog || level.getGameTime() - state.lastLogGameTime >= 20) {
            state.firstLog = false;
            state.lastLogGameTime = level.getGameTime();
            LOGGER.info("[chunk-prefetch] player {} mode={} dir=({},{}) view={} candidates={} tickets={} pending={}",
                    player.getGameProfile().getName(), state.idle ? "idle" : "window",
                    state.dirX, state.dirZ, viewDist, candidates.size(), tickets, next.size());
        }
    }

    public static String statusText() {
        return "prefetch(tickets=" + TICKETS.sum() + " fullSkip=" + SKIPPED_FULL.sum()
                + " recompute=" + RECOMPUTES.sum() + " idleIn=" + IDLE_ENTERS.sum()
                + " idleOut=" + IDLE_EXITS.sum() + ")";
    }

    private static final class State {
        boolean initialized;
        double lastX;
        double lastZ;
        int lastChunkX;
        int lastChunkZ;
        int dirX;
        int dirZ;
        long lastGameTime;
        long lastRecomputeGameTime;
        long lastLogGameTime;
        boolean firstLog;
        boolean idle;
        /** idle 判定窗口累计 tick（100 tick 内总位移 <2 块才进入）。 */
        int idleWindowTicks;
        /** idle 判定窗口累计位移（块）。 */
        int idleDistance;
        boolean windowDirty;
        /** 当前窗口内已投票坐标。 */
        Set<Long> pending = new HashSet<>();
        /** 上次退出 idle 的游戏刻（重入宽限，防块边界抖振）。 */
        long lastIdleExitGameTime;

        void init(ServerPlayer player, long gameTime) {
            this.initialized = true;
            this.lastX = player.getX();
            this.lastZ = player.getZ();
            ChunkPos c = player.chunkPosition();
            this.lastChunkX = c.x;
            this.lastChunkZ = c.z;
            this.lastGameTime = gameTime;
            this.lastRecomputeGameTime = gameTime;
            this.firstLog = true;
            // 冷启动：登录即进入 idle 环形预铺（落地玩家静止，环 [11,18] 立即 FULL 预铺；
            // 一旦跨块立即切方向性窗口）。方向未知也不阻塞环形。
            this.idle = PRTSFeaturesConfig.chunkPrefetchIdleEnabled;
        }
    }
}
