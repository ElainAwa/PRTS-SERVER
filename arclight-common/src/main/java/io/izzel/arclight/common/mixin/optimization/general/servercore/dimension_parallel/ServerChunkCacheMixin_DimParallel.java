/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Breaks the sync-chunk-load deadlock for dimension tick workers: vanilla
 * {@code getChunk} joins a main-thread supplyAsync that can never complete while
 * the main thread waits at the barrier. The worker-side lookup reads the vanilla
 * {@code lastChunk} ring buffer and {@code visibleChunkMap}, returning an
 * {@link EmptyLevelChunk} (air) only for genuinely missing chunks.
 * priority=2000 so it runs after Lithium's same-method injection at 1000.
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class ServerChunkCacheMixin_DimParallel implements io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheDemandBridge,
        io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkDemand");

    /** 超时日志去重（坐标 → 上次警告时间戳），防 isRainingAt 类每 tick 调用刷屏。 */
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> prts$lastTimeoutLog = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 强制加载持久票：UNKNOWN 票 1 tick 过期（purgeStaleTickets），超时返回后生成会被
     * 中止、进度丢失；此票永不超时，保证全新块生成在后台完成（下轮读取命中即收敛），
     * FULL 命中后由调用方显式 removeTicket 释放（防区块泄漏）。
     */
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> PRTS_FORCE_LOAD =
            net.minecraft.server.level.TicketType.create("prts_force_load",
                    java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private ChunkMap chunkMap;

    @Shadow
    @Final
    private DistanceManager distanceManager;

    @Shadow
    @Final
    private long[] lastChunkPos;

    @Shadow
    @Final
    private ChunkStatus[] lastChunkStatus;

    @Shadow
    @Final
    private ChunkAccess[] lastChunk;

    @Shadow
    private boolean runDistanceManagerUpdates() {
        throw new AssertionError();
    }

    @Redirect(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;"))
    private Object arclight$dimParallelChunkGet(CompletableFuture<?> future, int x, int z, ChunkStatus status, boolean required) {
        if (!DimensionTickManager.isDimensionTickThread() && !RegionTickManager.isRegionWorker()) {
            // 主线程走 vanilla 阻塞语义，worldgen 在 Worker 线程池执行，join 不会死锁
            return future.join();
        }        Object now = future.getNow(null);
        if (now != null) {
            return now;
        }
        try {
            // visibleChunkMap 是 volatile 整体替换；二次身份复核排除卸载瞬间的陈旧 holder
            //（旧 chunk 的方块实体在卸载后会被清空，块状态仍在，会造成 getBlockState 命中而 getBlockEntity 为空）。
            long key = ChunkPos.asLong(x, z);
            ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
            if (holder != null) {
                ChunkAccess cached = holder.getFullChunkFuture().getNow(null) == null
                        ? null : holder.getFullChunkFuture().getNow(null).orElse(null);
                if (cached != null && this.chunkMap.visibleChunkMap.get(key) == holder) {
                    return cached;
                }
            }
            // 卸载中区块仍在 updatingChunkMap 可见，恢复真实数据读。
            ChunkAccess existing = arclight$existingChunkRead(x, z);
            if (existing != null) {
                return existing;
            }
        } catch (Throwable t) {
            // 任何异常回退空气, 绝不让 worker 卡死/崩溃。
        }
        // Chunk 确实缺失且主线程在 barrier: 返回空气而非死锁。EmptyLevelChunk 是合法
        // ChunkAccess(getBlockState → air)。Biome 缓存 THE_VOID holder: 之前每个 miss 都
        // 采样 3D 噪声, 探索型 AI 在区域 worker 上反复查询缺失区块会形成噪声风暴。
        return new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level));
    }

    /**
     * 统一异步调度：并行开启时拦截所有 getChunk（含主线程），未就绪不再进入
     * 原版生成管线（避免 applyStep/completeFuture 竞争自旋）——注册需求 + 等待
     * future，required 有界等待 50ms，超时返回空壳；生成完成经 completeChunk 通知。
     */
    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true)
    private void arclight$v03AsyncChunkGet(int x, int z, ChunkStatus status, boolean required,
                                           CallbackInfoReturnable<ChunkAccess> cir) {
        if (!PRTSFeaturesConfig.parallelDimension && !PRTSFeaturesConfig.parallelRegion) {
            return;
        }
        if (status != ChunkStatus.FULL) {
            return;
        }
        ChunkAccess cached;
        try {
            cached = arclight$snapshotRead(x, z);
        } catch (Throwable t) {
            cached = null;
        }
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        // 主线程：M2 死锁防护——vanilla join 会等生成链推进，而 M2 的 reschedule 消化
        // 依赖主线程 runGenerationTasks（getChunk join 期间不跑）→ 生成永不完成 → 死锁。
        // required=true：有界等待 + 主线程迷你 tick（消化 reschedule + drain 需求），
        // 生成照常推进；超时返回空壳。required=false：提交需求立即空壳（不等待）。
        if (!DimensionTickManager.isDimensionTickThread() && !RegionTickManager.isRegionWorker()) {
            // required=false（loadDemand 等内部调用）不提交需求：需求由队列调用方管理，
            // 重复 submit 会形成 poll→submit→poll 死循环。直接返回空壳。
            if (!required) {
                cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
                return;
            }
            // required=true（玩家/登录/spawn 准备/强制加载）：复刻 vanilla
            // getChunkFutureMainThread 的强制加载语义——先加持久 ticket（级别 33）
            // 保证生成锥域 holder 存在（直接 scheduleGenerationTask 会因 acquireGeneration
            // 无空检查 NPE），再主线程迷你循环推进生成链：runDistanceManagerUpdates
            // （runAllUpdates 建 holder 锥域 → runGenerationTasks → M2 submit + reschedule
            // 消化）驱动 M2 任务图，有界等待 FULL 完成。
            // 与 vanilla UNKNOWN 票（1 tick 即过期，purge 后生成中止）不同：持久票保证
            // 全新块生成在后台完成，1s 超时返回空壳后下轮读取命中（收敛），FULL 命中
            // 后立即移除防泄漏。
            long key = ChunkPos.asLong(x, z);
            ChunkPos pos = new ChunkPos(x, z);
            this.distanceManager.addTicket(PRTS_FORCE_LOAD, pos,
                    ChunkLevel.byStatus(ChunkStatus.FULL), pos);
            // 等待窗口只覆盖「快速可完成」的加载（磁盘读/已生成）：全新块的 FULL 锥域生成
            // 需数秒（mod 结构多），等满超时是纯浪费——调用方（如 twilightforest isRainingAt
            // 每 tick 强制加载）会反复卡死主线程（TPS→1）。短超时快速失败，持久票保证
            // 后台生成完成，调用方重试/下轮读取即命中收敛。
            long deadline = System.nanoTime() + 5_000_000L;
            boolean schedWarned = false;
            while (System.nanoTime() < deadline) {
                this.runDistanceManagerUpdates();
                ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
                if (holder == null) {
                    holder = this.chunkMap.updatingChunkMap.get(key);
                }
                if (holder != null) {
                    // 每迭代重试提交（幂等：已有同目标任务则不重复建）。一次性提交在邻居
                    // holder 未齐时 acquireGeneration NPE 会静默丢任务（异常被调用方吞掉）
                    // ——生成永不开始。此处捕获并打日志，下轮迭代自动重试。
                    try {
                        holder.scheduleChunkGenerationTask(ChunkStatus.FULL, this.chunkMap);
                    } catch (Throwable t) {
                        if (!schedWarned) {
                            schedWarned = true;
                            LOGGER.warn("[chunk-demand] scheduleChunkGenerationTask failed at ({}, {}), retrying", x, z, t);
                        }
                    }
                }
                ChunkAccess now = arclight$snapshotRead(x, z);
                if (now != null) {
                    // FULL 完成：释放持久票（vanilla UNKNOWN 1 tick 后同样释放），防区块泄漏
                    this.distanceManager.removeTicket(PRTS_FORCE_LOAD, pos,
                            ChunkLevel.byStatus(ChunkStatus.FULL), pos);
                    cir.setReturnValue(now);
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long waitedMs = (System.nanoTime() - (deadline - 5_000_000L)) / 1_000_000L;
            boolean inVisible = this.chunkMap.visibleChunkMap.containsKey(key);
            boolean inUpdating = this.chunkMap.updatingChunkMap.containsKey(key);
            // 诊断：holder 的生成任务/future 状态——区分「从未提交任务」与「任务卡住/生成慢」
            String taskInfo = " n/a";
            ChunkHolder hh = this.chunkMap.visibleChunkMap.get(key);
            if (hh == null) {
                hh = this.chunkMap.updatingChunkMap.get(key);
            }
            if (hh instanceof io.izzel.arclight.common.optimization.general.chunksystem.PRTSChunkSystemHolderAware aware) {
                var taskRef = aware.prts$task().get();
                taskInfo = " task=" + (taskRef == null ? "null" : taskRef.targetStatus.getName());
            }
            // 日志去重：同一坐标 10s 内只打一条（isRainingAt 类每 tick 调用会刷屏）
            long nowNanos = System.nanoTime();
            long logKey = key;
            long last = prts$lastTimeoutLog.getOrDefault(logKey, 0L);
            if (nowNanos - last > 10_000_000_000L) {
                prts$lastTimeoutLog.put(logKey, nowNanos);
                LOGGER.warn("[chunk-demand] force-load wait timeout at ({}, {}) dim={} after {}ms (visible={} updating={}{}); ticket kept for background gen",
                        x, z, this.level.dimension().location(), waitedMs, inVisible, inUpdating, taskInfo);
            }
            cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
            return;
        }
        // 卸载 promotion 中的区块仍在 updatingChunkMap：实体 tick 期间应读到真实数据
        ChunkAccess existing;
        try {
            existing = arclight$existingChunkRead(x, z);
        } catch (Throwable t) {
            existing = null;
        }
        if (existing != null) {
            cir.setReturnValue(existing);
            return;
        }
        // 主线程在 barrier 中无法 drain 需求，worker 等待必然超时：只提交需求，
        // 立即返回空壳（下 tick 主线程 drain 生成完成后 worker 就能读到真实区块）。
        ChunkDemandQueue.submit(this.level, this.chunkMap, x, z, false);
        cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
    }

    /**
     * 无锁快照读：只信 visibleChunkMap 中 FULL 状态已完成的 holder，二次身份复核排除
     * 卸载瞬间的陈旧 holder。注意读 FULL 状态而非 getFullChunkFuture（BLOCK_TICKING）：
     * 后者需 3×3 邻居 FULL，单块强制加载（邻居仅 border 级 ticket）时永不完成——曾导致
     * 生成早已完成却永远读不到、逐块 1s 超时重试（登录/殖民地同步卡死）。
     */
    @Unique
    private ChunkAccess arclight$snapshotRead(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
        if (holder != null) {
            ChunkAccess cached = holder.getChunkIfPresent(ChunkStatus.FULL);
            // holder 若已离开当前可见表，旧 chunk 的方块实体可能已被卸载清空
            if (cached != null && this.chunkMap.visibleChunkMap.get(key) == holder) {
                return cached;
            }
        }
        return null;
    }

    /** 读取卸载中/未 promotion 区块（updatingChunkMap），无阻塞、worker 安全。 */
    @Unique
    private ChunkAccess arclight$existingChunkRead(int x, int z) {
        ChunkHolder holder = this.chunkMap.updatingChunkMap.get(ChunkPos.asLong(x, z));
        if (holder != null) {
            return holder.getChunkIfPresentUnchecked(ChunkStatus.FULL);
        }
        return null;
    }

    /** 区块是否仍可读（已加载或卸载中）；生成未完成返回 false。 */
    @Override
    public boolean arclight$hasLiveChunk(int x, int z) {
        return arclight$snapshotRead(x, z) != null || arclight$existingChunkRead(x, z) != null;
    }

    @Override
    public void arclight$drainDeferredReschedules() {
        if (this.chunkMap instanceof io.izzel.arclight.common.optimization.general.chunksystem.PRTSChunkMapRescheduleAware aware) {
            aware.prts$drainDeferredReschedules();
        }
    }

    /** worker 安全读取：快照读优先，updating 兜底；无活区块返回 null。 */
    @Override
    public net.minecraft.world.level.chunk.ChunkAccess arclight$getChunkForRead(int x, int z) {
        net.minecraft.world.level.chunk.ChunkAccess snapshot = arclight$snapshotRead(x, z);
        if (snapshot != null) {
            return snapshot;
        }
        return arclight$existingChunkRead(x, z);
    }

    /** 提交异步加载需求（与 getChunk miss 行为一致：仅入队，主线程 tick 消费）。 */
    @Override
    public void arclight$submitChunkDemand(int x, int z) {
        ChunkDemandQueue.submit(this.level, this.chunkMap, x, z, false);
    }

    /**
     * 主线程 drain 的落地实现：按配额消费需求，非阻塞触发生成（required=false，
     * 不等待完成）。成功后写入 lastChunk 环形缓存供 vanilla getChunkNow 使用
     * （worker 侧读取路径不读环形缓存，避免卸载后陈旧 chunk 的方块实体空表）。
     * 仅主线程调用（调用方已保证 barrier 结束）。
     */
    @Override
    public void arclight$drainChunkDemands() {
        if (DimensionTickManager.isDimensionTickThread() || RegionTickManager.isRegionWorker()) {
            return;
        }
        int budget = ChunkDemandQueue.maxPerTick;
        int loaded = 0;
        long start = System.nanoTime();
        ChunkPos pos;
        // 常规配额循环。
        while (loaded < budget && (pos = ChunkDemandQueue.poll(this.level)) != null) {
            if (arclight$loadDemand(pos)) {
                loaded++;
            }
        }
        // 最低保证窗口:配额尽后仍至少排空 minDrainMs,防低 TPS 死亡螺旋。
        long minDrainNs = ChunkDemandQueue.minDrainMs * 1_000_000L;
        if (minDrainNs > 0L && System.nanoTime() - start < minDrainNs) {
            while (System.nanoTime() - start < minDrainNs
                    && (pos = ChunkDemandQueue.poll(this.level)) != null) {
                if (arclight$loadDemand(pos)) {
                    loaded++;
                }
            }
        }
        io.izzel.arclight.common.optimization.general.servercore.ChunkLoadStats.installPass(loaded, System.nanoTime() - start);
        ChunkDemandQueue.afterDrain(this.level);
    }

    /** 消费一个需求坐标并写入 lastChunk 环形缓存；返回是否成功落地为 FULL。 */
    @Unique
    private boolean arclight$loadDemand(ChunkPos pos) {
        ChunkAccess chunk = this.level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        if (chunk instanceof LevelChunk lc) {
            long key = ChunkPos.asLong(pos.x, pos.z);
            // floorMod 处理负 long key（x/z 大时 asLong 高位为负），避免负数槽位越界。
            int slot = Math.floorMod(key, this.lastChunkPos.length);
            this.lastChunkPos[slot] = key;
            this.lastChunkStatus[slot] = ChunkStatus.FULL;
            this.lastChunk[slot] = lc;
            return true;
        }
        return false;
    }

    @Unique
    private static volatile Holder<Biome> arclight$voidBiome;

    @Unique
    private static Holder<Biome> arclight$voidBiome(ServerLevel level) {
        Holder<Biome> cached = arclight$voidBiome;
        if (cached == null) {
            // 启动后 registry 已冻结，取一次缓存即可（双检锁避免竞态）。
            synchronized (ServerChunkCacheMixin_DimParallel.class) {
                cached = arclight$voidBiome;
                if (cached == null) {
                    cached = level.registryAccess().registryOrThrow(Registries.BIOME)
                            .getHolderOrThrow(Biomes.THE_VOID);
                    arclight$voidBiome = cached;
                }
            }
        }
        return cached;
    }
}
