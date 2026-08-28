/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
 * 玩家方向区块预铺（v03：依赖根预铺引擎）。
 *
 * <p>对玩家前方窗口（默认 16×16）投 <b>STRUCTURE_STARTS 级票</b>
 * （level = {@code ChunkLevel.byStatus(STRUCTURE_STARTS)} = 41）：票自动创建
 * holder 并把块目标定在 structure_starts——生成任务图只展开 EMPTY + 根，
 * 由 M2 以预铺优先级档（56-63）执行；玩家接近后玩家的 FULL 票（33）覆盖，
 * 既有 reschedule 机制无缝升级到 FULL。这是「8 格依赖根提前铺满」的载体，
 * 与旧版 FULL 票线预铺（把前方块拉到 FULL，成本拖死波前）本质不同。
 *
 * <p>节流与上限：窗口重算仅在 跨块 ≥ window-step 或 距上次重算 ≥
 * window-recompute-ticks 时触发；每次补货到 max-pending 上限、最近优先。
 * idle 预生成（玩家静止 100 tick 位移 <2 块进入、单次 ≥4 块退出，滞回）在
 * 视距外环铺根。全部逻辑主线程执行，纯增益。
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
        if (state.dirX == 0 && state.dirZ == 0) {
            return; // 尚未跨块，方向不可定
        }

        // idle 判定（滞回：连续 100 tick 位移<2 块进入；单次位移≥4 块退出）
        if (PRTSFeaturesConfig.chunkPrefetchIdleEnabled) {
            if (!state.idle) {
                if (moved < 2) {
                    state.idleCandidateTicks += elapsed;
                    if (state.idleCandidateTicks >= PRTSFeaturesConfig.chunkPrefetchIdleEnterTicks) {
                        state.idle = true;
                        state.idleCandidateTicks = 0;
                        IDLE_ENTERS.increment();
                        LOGGER.info("[chunk-prefetch] {} entered idle pre-generation (radius={}, perTick={})",
                                player.getGameProfile().getName(),
                                PRTSFeaturesConfig.chunkPrefetchIdleRadius,
                                PRTSFeaturesConfig.chunkPrefetchIdlePerTick);
                    }
                } else {
                    state.idleCandidateTicks = 0;
                }
            } else if (moved >= PRTSFeaturesConfig.chunkPrefetchIdleExitBlocks) {
                state.idle = false;
                state.windowDirty = true; // 立即切回方向性窗口
                IDLE_EXITS.increment();
                LOGGER.info("[chunk-prefetch] {} exited idle (moved {} blocks), back to directional window",
                        player.getGameProfile().getName(), moved);
            }
        }

        // 窗口重算触发：脏标记 / 超时 / （非 idle 且）跨块达阈值
        boolean recompute = state.windowDirty
                || gameTime - state.lastRecomputeGameTime >= PRTSFeaturesConfig.chunkPrefetchWindowRecomputeTicks
                || (!state.idle && moved >= PRTSFeaturesConfig.chunkPrefetchWindowStep);
        if (!recompute) {
            return;
        }
        int sinceRecompute = (int) (gameTime - state.lastRecomputeGameTime);
        state.windowDirty = false;
        state.lastRecomputeGameTime = gameTime;
        RECOMPUTES.increment();
        recomputeWindow(player, level, source, state, sinceRecompute);
    }

    /**
     * 全量重算窗口并补货（主线程；跨块≥2 或 40 tick 触发，不每 tick 检查）。
     * 候选按到玩家切比雪夫距离升序（最近优先）；已 FULL / 已有票跳过；
     * 补货上限 max-pending；idle 模式按 perTick × 经过 tick 平滑限速。
     */
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
                // 新块：投 STRUCTURE_STARTS 级票（重复投同票幂等，可省）
                ((PrefetchTicketSink) source).prts$addPrefetchTicket(ticket(), pos,
                        ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS), Unit.INSTANCE);
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
        int idleCandidateTicks;
        boolean windowDirty;
        Set<Long> pending = new HashSet<>();

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
        }
    }
}
