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

/** 并行下 getChunk 的非阻塞读取与主线程有界等待。 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class ServerChunkCacheMixin_DimParallel implements io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheDemandBridge,
        io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkDemand");

    /** 主线程强制加载等待按维度+坐标冷却，防止高频 miss 饿死主线程。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> PRTS_WAIT_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 超时日志去重（维度+坐标 → 上次警告时间戳），防 isRainingAt 类每 tick 调用刷屏。 */
    @Unique
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> prts$lastTimeoutLog = new java.util.concurrent.ConcurrentHashMap<>();

    /** 尚未释放的强制加载票集合（后台完成后在任意命中路径回收，防永久票泄漏）。 */
    @Unique
    private final java.util.Set<Long> arclight$outstandingForceLoads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 主线程 drain 内部调用 getChunk 时绕过本 mixin 拦截，走 vanilla 提交路径。 */
    @Unique
    private final ThreadLocal<Boolean> arclight$internalLoad = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 每 tick 主线程强制加载等待总预算（preview 类大面积 miss 时防饿死 watchdog）。 */
    @Unique
    private long arclight$waitBudgetTick = -1L;

    @Unique
    private long arclight$waitBudgetNanos;

    /** 每 tick 只推进一次生成泵，避免扫描类模组逐格 getBlockState 时反复跑满生成队列。 */
    @Unique
    private long arclight$generationPumpTick = -1L;

    /** 强制加载持久票：保证超时后后台继续生成，FULL 命中时回收。 */
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
        // 缺失且 barrier 中：返回空气；Biome 缓存 void 避免噪声风暴
        return new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level));
    }

    /** 未就绪区块统一走需求队列与有界等待。 */
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
        // 主线程 drain 的内部加载调用：绕过本拦截走 vanilla 提交路径
        if (Boolean.TRUE.equals(this.arclight$internalLoad.get())) {
            return;
        }
        // 主线程保持 vanilla 同步生成语义：required=true 必须拿到真区块。
        // 空壳回退会让按 BlockState 身份比较的模组（rubberworks sapper）死循环。
        if (!DimensionTickManager.isDimensionTickThread() && !RegionTickManager.isRegionWorker()) {
            return;
        }
        long key = ChunkPos.asLong(x, z);
        ChunkAccess cached;
        try {
            cached = arclight$snapshotRead(x, z);
        } catch (Throwable t) {
            cached = null;
        }
        if (cached != null) {
            // 后台生成完成后从任意命中路径回收强制加载票
            if (this.arclight$outstandingForceLoads.remove(key)) {
                ChunkPos pos = new ChunkPos(x, z);
                this.distanceManager.removeTicket(PRTS_FORCE_LOAD, pos,
                        ChunkLevel.byStatus(ChunkStatus.FULL), pos);
            }
            cir.setReturnValue(cached);
            return;
        }
        // required=true 走有界等待并驱动生成；required=false 直接空壳
        if (!DimensionTickManager.isDimensionTickThread() && !RegionTickManager.isRegionWorker()) {
            if (!required) {
                cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
                return;
            }
            // 持久票保证后台继续生成，FULL 命中后回收
            ChunkPos pos = new ChunkPos(x, z);
            if (this.arclight$outstandingForceLoads.add(key)) {
                this.distanceManager.addTicket(PRTS_FORCE_LOAD, pos,
                        ChunkLevel.byStatus(ChunkStatus.FULL), pos);
            }
            // 同坐标 20 tick 内只等待一次
            long gameTime = this.level.getGameTime();
            String cooldownKey = this.level.dimension().location().toString() + '/' + x + '/' + z;
            Long lastWait = PRTS_WAIT_COOLDOWN.get(cooldownKey);
            if (lastWait != null && gameTime - lastWait < 20L) {
                cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
                return;
            }
            // 冷却表上限保护
            if (PRTS_WAIT_COOLDOWN.size() >= 65536) {
                PRTS_WAIT_COOLDOWN.clear();
            }
            PRTS_WAIT_COOLDOWN.put(cooldownKey, gameTime);
            // 每 tick 等待预算，耗尽后本 tick 剩余 miss 直接空壳
            if (this.arclight$waitBudgetTick != gameTime) {
                this.arclight$waitBudgetTick = gameTime;
                this.arclight$waitBudgetNanos = 5_000_000L;
            }
            if (this.arclight$waitBudgetNanos <= 0L) {
                cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
                return;
            }
            long waitStart = System.nanoTime();
            // 短窗口快速失败，后台生成完成后续读命中收敛
            long deadline = waitStart + 1_000_000L;
            boolean schedWarned = false;
            java.util.concurrent.locks.ReentrantLock genLock =
                    io.izzel.arclight.common.optimization.general.servercore.ChunkGenerationOwnerLock.lock(this.level);
            while (System.nanoTime() < deadline) {
                genLock.lock();
                try {
                    this.runDistanceManagerUpdates();
                    // 等待期间推进生成泵；同 tick 只推一次，扫描类模组逐格
                    // getBlockState 时不得每格都重跑整条生成队列（生产 12:13 卡死）。
                    if (this.arclight$generationPumpTick != gameTime) {
                        this.arclight$generationPumpTick = gameTime;
                        try {
                            this.chunkMap.runGenerationTasks();
                        } catch (Throwable ignored) {
                        }
                    }
                    ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
                    if (holder == null) {
                        holder = this.chunkMap.updatingChunkMap.get(key);
                    }
                    if (holder != null) {
                        // 每迭代重试提交；一次性提交在邻居 holder 未齐时可能 NPE
                        try {
                            holder.scheduleChunkGenerationTask(ChunkStatus.FULL, this.chunkMap);
                        } catch (Throwable t) {
                            if (!schedWarned) {
                                schedWarned = true;
                                LOGGER.warn("[chunk-demand] scheduleChunkGenerationTask failed at ({}, {}), retrying", x, z, t);
                            }
                        }
                    }
                } finally {
                    genLock.unlock();
                }
                ChunkAccess now = arclight$snapshotRead(x, z);
                if (now != null) {
                    // FULL 完成：释放持久票（vanilla UNKNOWN 1 tick 后同样释放），防区块泄漏
                    this.arclight$outstandingForceLoads.remove(key);
                    this.distanceManager.removeTicket(PRTS_FORCE_LOAD, pos,
                            ChunkLevel.byStatus(ChunkStatus.FULL), pos);
                    this.arclight$waitBudgetNanos -= Math.max(0L, System.nanoTime() - waitStart);
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
            this.arclight$waitBudgetNanos -= Math.max(0L, System.nanoTime() - waitStart);
            long waitedMs = (System.nanoTime() - waitStart) / 1_000_000L;
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
            // 日志去重：同维度同坐标 10s 内只打一条（isRainingAt 类每 tick 调用会刷屏）
            long nowNanos = System.nanoTime();
            String logKey = cooldownKey;
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

    /** 无锁快照读：只信 FULL 完成且身份复核通过的 holder。 */
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

    /** 主线程限量消费需求并触发非阻塞生成。 */
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
        long key = ChunkPos.asLong(pos.x, pos.z);
        ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
        if (holder == null) {
            holder = this.chunkMap.updatingChunkMap.get(key);
        }
        if (holder != null) {
            try {
                holder.scheduleChunkGenerationTask(ChunkStatus.FULL, this.chunkMap);
            } catch (Throwable ignored) {
            }
        }
        // 绕过本 mixin 的 HEAD 拦截：主线程内部加载走 vanilla 提交路径，避免
        // 拦截短路返回 EmptyLevelChunk 被误当成 FULL 写入 lastChunk
        ChunkAccess chunk;
        this.arclight$internalLoad.set(Boolean.TRUE);
        try {
            chunk = this.level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
        } finally {
            this.arclight$internalLoad.set(Boolean.FALSE);
        }
        ChunkAccess result = chunk;
        if (result == null) {
            result = arclight$snapshotRead(pos.x, pos.z);
        }
        if (result instanceof LevelChunk lc && !(result instanceof EmptyLevelChunk)) {
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
