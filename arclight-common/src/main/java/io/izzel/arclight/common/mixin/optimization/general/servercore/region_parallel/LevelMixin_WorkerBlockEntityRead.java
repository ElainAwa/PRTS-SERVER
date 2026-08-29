/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.region_parallel;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 并行 worker 上放行 {@code Level.getBlockEntity}：vanilla 硬性线程守卫
 * （非主线程直接返回 null）会让区域 worker 上的实体（殖民地 NPC 等）拿不到
 * 方块实体而自毁。worker 路径改为非阻塞读活区块（visible/updating 已完成
 * future），区块未加载时返回 null，与 vanilla 语义一致。
 */
@Mixin(Level.class)
public abstract class LevelMixin_WorkerBlockEntityRead {

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
            at = @At("HEAD"), cancellable = true)
    private void arclight$workerBlockEntityRead(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (!RegionTickManager.isRegionWorker() && !DimensionTickManager.isDimensionTickThread()) {
            return;
        }
        Level self = (Level) (Object) this;
        if (self.isOutsideBuildHeight(pos)) {
            cir.setReturnValue(null);
            return;
        }
        if (self.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge) {
            ChunkAccess chunk = bridge.arclight$getChunkForRead(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk instanceof LevelChunk levelChunk) {
                cir.setReturnValue(levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE));
            } else {
                cir.setReturnValue(null);
            }
        }
    }
}
