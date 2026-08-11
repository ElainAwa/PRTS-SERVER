/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主线程 tick 末尾消费 chunk 需求队列（统一需求调度，仅主线程执行；
 * 维度并行时主线程 drain 由 PRE 阶段负责）。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_ChunkDemandDrain {

    @Inject(method = "tickChunks", at = @At("RETURN"))
    private void arclight$drainChunkDemands(CallbackInfo ci) {
        if (!DimensionTickManager.inDimensionTick() && !RegionTickManager.inRegionTick()) {
            ((io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheDemandBridge) (Object) this).arclight$drainChunkDemands();
        }
    }
}
