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
 * PRTS dimension parallelism (P2 experiment, AI-created): break the sync-chunk-load
 * deadlock for dimension tick workers.
 *
 * <p>Vanilla {@code ServerChunkCache.getChunk} routes non-main-thread callers through
 * {@code supplyAsync(..., mainThreadProcessor).join()}. On a dimension tick worker the
 * main thread is waiting at the dimension barrier, so that join never completes → the
 * watchdog kills the server after 60s.
 *
 * <p>Important subtlety (fixed in the P1 x P2 stacking round): that supplyAsync future
 * is created fresh on every call and is only completed when the main-thread executor
 * actually runs the task — which never happens while the main thread is at the barrier.
 * {@link CompletableFuture#getNow} therefore returns null even for chunks that ARE
 * already loaded, which made entities fall through the floor (summoned mobs sank into
 * the void on the worker while setblock on the main thread succeeded).
 * {@link ServerChunkCache#getChunkNow} is NOT usable either: it returns null on any
 * non-main thread. The working lookup first tries the vanilla {@code lastChunkPos/
 * lastChunkStatus/lastChunk} ring buffer (a plain array read, atomic under concurrent
 * main-thread writes; worst case: stale value or null), then reads the
 * {@code visibleChunkMap} — safe because it is a volatile reference replaced wholesale
 * on promoteChunkMap, so a worker reads an immutable old snapshot. Only a genuinely
 * missing chunk gets an {@link EmptyLevelChunk} (air) for one tick instead of
 * deadlocking (the documented P2 "unloaded chunk access" boundary, see
 * docs/parallel-phase2-dimension-parallelism-v01.md §5).
 * <p>priority=2000: Lithium (net.caffeinemc.mods.lithium) also mixins
 * {@code ServerChunkCache.getChunk} at priority 1000; same-priority injections into
 * an already-merged method are rejected by mixin ("cannot inject ... merged by ...").
 * A higher priority lets this injection run after Lithium's (which only prepends a
 * chunk cache check at HEAD and keeps the join call site).</p>
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
            // 任何异常回退空气, 绝不让 worker 卡死/崩溃(watchdog 60s 防线)。
        }
        // Chunk genuinely missing and the main thread is at the barrier: return air
        // instead of deadlocking. EmptyLevelChunk is a valid ChunkAccess (getBlockState → air).
        // Biome is a cached THE_VOID holder: the previous getUncachedNoiseBiome call sampled
        // 3D noise on every miss, and exploration-style AI (zombie RemoveBlockGoal) hammering
        // missing chunks on region workers turned that into a PerlinNoise storm (watchdog hang,
        // see docs/parallel-removeblockgoal-worker-evaluation.md).
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
