/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * waitUntilNextTick（spawn 准备循环/空闲等待）时消化 M2 挂起的生成重调度：
 * vanilla 生成链由 worldgen worker 自驱，M2 的后续层提交依赖主线程消化
 * deferReschedule，而 spawn 准备阶段主线程不跑 runGenerationTasks →
 * 生成链断裂（Preparing spawn area 卡 0%）。在此统一消化，推进生成链。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_RescheduleDrain {

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void prts$drainReschedulesDuringWait(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge) {
                bridge.arclight$drainDeferredReschedules();
            }
        }
    }
}
