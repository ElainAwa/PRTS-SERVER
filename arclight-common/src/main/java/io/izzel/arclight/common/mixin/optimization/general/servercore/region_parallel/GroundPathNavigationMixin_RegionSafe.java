/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 原版 GroundPathNavigation.createPath 第一步用 getChunkNow 读目标区块——
 * 该方法仅主线程可用，region worker 上恒 null，createPath 直接返回 null，
 * 所有地面生物在区域并行下永远拿不到路径（"生物一动不动"的根因）。
 * 此处把该调用重定向为 worker 安全的快照读。
 */
@Mixin(GroundPathNavigation.class)
public abstract class GroundPathNavigationMixin_RegionSafe extends PathNavigation {

    private GroundPathNavigationMixin_RegionSafe(Mob mob, Level level) {
        super(mob, level);
    }

    @Redirect(method = "createPath(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/pathfinder/Path;",
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
