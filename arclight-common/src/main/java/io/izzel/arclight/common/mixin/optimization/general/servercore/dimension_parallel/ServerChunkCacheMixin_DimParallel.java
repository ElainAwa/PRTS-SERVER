/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

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
import org.spongepowered.asm.mixin.injection.Redirect;

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
public abstract class ServerChunkCacheMixin_DimParallel {

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
            return future.join();
        }
        Object now = future.getNow(null);
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
