/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PathNavigationRegion 构造器用 getChunkNow 填充 A* 区块表——worker 上恒 null，
 * allEmpty=true，A* 把世界看成全空 → 全部 1 节点路径、生物不动。重定向为 worker 安全读。
 */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin_RegionSafe {

    @Shadow
    protected net.minecraft.world.level.Level level;

    @Redirect(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkSource;getChunkNow(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk arclight$workerSafeChunkNow(ChunkSource source, int x, int z) {
        LevelChunk chunk = source.getChunkNow(x, z);
        if (chunk != null) {
            return chunk;
        }
        if (this.level instanceof ServerLevel && source instanceof ServerChunkCache sc) {
            ChunkAccess ca = ((ServerChunkCacheRegionBridge) sc).arclight$getChunkForRead(x, z);
            if (ca instanceof LevelChunk lc) {
                return lc;
            }
        }
        return null;
    }
}
