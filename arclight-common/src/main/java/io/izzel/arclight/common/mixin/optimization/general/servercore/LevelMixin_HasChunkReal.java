/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 hasChunkAt 语义：并行模式下 getChunk(required=false) 对缺失块返回 EmptyLevelChunk
 * （它 extends LevelChunk），vanilla 的 instanceof 判定被误判为 true——调用方（如
 * twilightforest BlockHooks.isRainingAt，每 tick 调用）因此继续 getChunkAt(required=true)
 * → 每 tick 阻塞等待 → 主线程卡死（TPS→1，登录/进服"很久"）。
 * 此处改为真实 FULL 状态判定（空壳不算存在），未加载时返回 false 让调用方走非阻塞路径。
 */
@Mixin(Level.class)
public abstract class LevelMixin_HasChunkReal {

    @Inject(method = "hasChunkAt", at = @At("HEAD"), cancellable = true)
    private void prts$realHasChunk(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel serverLevel) {
            if (serverLevel.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge) {
                cir.setReturnValue(bridge.arclight$hasLiveChunk(pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
    }
}
