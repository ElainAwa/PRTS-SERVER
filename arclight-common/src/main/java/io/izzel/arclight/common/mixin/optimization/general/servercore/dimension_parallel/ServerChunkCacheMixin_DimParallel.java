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
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
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

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private ChunkMap chunkMap;

    @Shadow
    @Final
    private long[] lastChunkPos;

    @Shadow
    @Final
    private ChunkStatus[] lastChunkStatus;

    @Shadow
    @Final
    private ChunkAccess[] lastChunk;

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
        // 主线程回退 vanilla getChunk，保证 required 区块阻塞生成（否则玩家进未生成区拿到空气）
        if (!DimensionTickManager.isDimensionTickThread() && !RegionTickManager.isRegionWorker()) {
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

    /** 无锁快照读：只信 visibleChunkMap 的已完成 full future，二次身份复核排除卸载瞬间的陈旧 holder。 */
    @Unique
    private ChunkAccess arclight$snapshotRead(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
        if (holder != null) {
            java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.LevelChunk>> full = holder.getFullChunkFuture();
            if (full != null) {
                net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.LevelChunk> r = full.getNow(null);
                if (r != null) {
                    ChunkAccess cached = r.orElse(null);
                    // holder 若已离开当前可见表，旧 chunk 的方块实体可能已被卸载清空
                    if (cached != null && this.chunkMap.visibleChunkMap.get(key) == holder) {
                        return cached;
                    }
                }
            }
        }
        return null;
    }

    /** 读取卸载中/未 promotion 区块（updatingChunkMap），无阻塞、worker 安全。 */
    @Unique
    private ChunkAccess arclight$existingChunkRead(int x, int z) {
        ChunkHolder holder = this.chunkMap.updatingChunkMap.get(ChunkPos.asLong(x, z));
        if (holder != null) {
            ChunkResult<LevelChunk> r = holder.getFullChunkFuture().getNow(null);
            if (r != null) {
                return r.orElse(null);
            }
        }
        return null;
    }

    /** 区块是否仍可读（已加载或卸载中）；生成未完成返回 false。 */
    @Override
    public boolean arclight$hasLiveChunk(int x, int z) {
        return arclight$snapshotRead(x, z) != null || arclight$existingChunkRead(x, z) != null;
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
