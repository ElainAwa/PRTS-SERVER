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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Breaks the sync-chunk-load deadlock for dimension tick workers: vanilla
 * {@code getChunk} joins a main-thread supplyAsync that can never complete while
 * the main thread waits at the barrier. The worker-side lookup reads the vanilla
 * {@code lastChunk} ring buffer and {@code visibleChunkMap}, returning an
 * {@link EmptyLevelChunk} (air) only for genuinely missing chunks.
 * priority=2000 so it runs after Lithium's same-method injection at 1000.
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
public abstract class ServerChunkCacheMixin_DimParallel implements io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheDemandBridge {

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
        if (!DimensionTickManager.inDimensionTick() && !RegionTickManager.inRegionTick()) {
            if (PRTSFeaturesConfig.parallelDimension || PRTSFeaturesConfig.parallelRegion) {
                // 并行开启时主线程 chunk 加载依赖并行 tick 的调度推进；若调度因主线程
                // 卡在 getChunk 而无法运行，future 永不完成会无限递归卡死。加超时兜底：
                // 超时返回空气，主线程恢复 tick 后调度继续推进该 chunk。
                try {
                    Object v = future.get(2, TimeUnit.SECONDS);
                    if (v != null) {
                        return v;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level));
                } catch (TimeoutException | java.util.concurrent.ExecutionException e) {
                    return new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level));
                }
            }
            return future.join();
        }        Object now = future.getNow(null);
        if (now != null) {
            return now;
        }
        try {
            // supplyAsync future 在主线程 barrier 期间永远不会完成(getNow 恒 null)。
            // 1. lastChunk 环形缓存(纯数组无锁读, 并发安全)。
            long key = ChunkPos.asLong(x, z);
            for (int i = 0; i < this.lastChunkPos.length; i++) {
                if (this.lastChunkPos[i] == key && this.lastChunkStatus[i] == ChunkStatus.FULL) {
                    ChunkAccess cached = this.lastChunk[i];
                    if (cached != null) {
                        return cached;
                    }
                }
            }
            // 2. visibleChunkMap 是 volatile + 整体替换引用(promoteChunkMap 从
            //    updatingChunkMap clone), worker 读旧快照安全; getFullChunkFuture
            //    是 CompletableFuture(getNow 非阻塞线程安全)。
            ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
            if (holder != null) {
                ChunkAccess cached = holder.getFullChunkFuture().getNow(null) == null
                        ? null : holder.getFullChunkFuture().getNow(null).orElse(null);
                if (cached != null) {
                    return cached;
                }
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
     * v03 统一异步调度：并行开启时拦截所有 getChunk（含主线程），未就绪不再进入
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
        ChunkAccess cached = arclight$snapshotRead(x, z);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        boolean mainThread = !DimensionTickManager.inDimensionTick() && !RegionTickManager.inRegionTick();
        CompletableFuture<ChunkAccess> future = ChunkDemandQueue.submitWait(this.level, this.chunkMap, x, z, mainThread);
        if (required) {
            ChunkAccess c = ChunkDemandQueue.await(future, 50);
            if (c != null) {
                cir.setReturnValue(c);
                return;
            }
        }
        cir.setReturnValue(new EmptyLevelChunk(this.level, new ChunkPos(x, z), arclight$voidBiome(this.level)));
    }

    /** 无锁快照读：lastChunk 环形缓存 + visibleChunkMap（并发安全，同 worker 分支逻辑）。 */
    @Unique
    private ChunkAccess arclight$snapshotRead(int x, int z) {
        long key = ChunkPos.asLong(x, z);
        for (int i = 0; i < this.lastChunkPos.length; i++) {
            if (this.lastChunkPos[i] == key && this.lastChunkStatus[i] == ChunkStatus.FULL) {
                ChunkAccess cached = this.lastChunk[i];
                if (cached != null) {
                    return cached;
                }
            }
        }
        ChunkHolder holder = this.chunkMap.visibleChunkMap.get(key);
        if (holder != null) {
            java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.LevelChunk>> full = holder.getFullChunkFuture();
            if (full != null) {
                net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.LevelChunk> r = full.getNow(null);
                if (r != null) {
                    ChunkAccess cached = r.orElse(null);
                    if (cached != null) {
                        return cached;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 主线程 drain 的落地实现：按配额消费需求，非阻塞触发生成（required=false，
     * 不等待完成），成功后写入 lastChunk 环形缓存，worker 下一 tick 立即可见。
     * 仅主线程调用（调用方已保证 barrier 结束）。
     */
    @Override
    public void arclight$drainChunkDemands() {
        if (DimensionTickManager.inDimensionTick() || RegionTickManager.inRegionTick()) {
            return;
        }
        int budget = ChunkDemandQueue.maxPerTick;
        int loaded = 0;
        ChunkPos pos;
        while (loaded < budget && (pos = ChunkDemandQueue.poll()) != null) {
            ChunkAccess chunk = this.level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk instanceof LevelChunk lc) {
                long key = ChunkPos.asLong(pos.x, pos.z);
                int slot = (int) (key % this.lastChunkPos.length);
                this.lastChunkPos[slot] = key;
                this.lastChunkStatus[slot] = ChunkStatus.FULL;
                this.lastChunk[slot] = lc;
                loaded++;
            }
        }
        ChunkDemandQueue.afterDrain();
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
