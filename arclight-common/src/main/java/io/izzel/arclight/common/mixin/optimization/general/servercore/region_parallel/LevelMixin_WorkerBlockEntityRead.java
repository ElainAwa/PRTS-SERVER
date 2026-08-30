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

/** worker 只读放行 getBlockEntity：读活区块已存在 BE，未加载返回 null。 */
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
                // 只读已存在 BE；worker 不能创建/注册方块实体
                cir.setReturnValue(levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK));
            } else {
                cir.setReturnValue(null);
            }
        }
    }
}
