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

/** 空闲等待时消化延迟的生成重调度，保持生成链推进。 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin_RescheduleDrain {

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void prts$drainReschedulesDuringWait(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource() instanceof ServerChunkCacheRegionBridge bridge) {
                // 与维度 worker 的生成驱动共用属主锁，避免并发驱动 vanilla ChunkMap 管线
                java.util.concurrent.locks.ReentrantLock genLock =
                        io.izzel.arclight.common.optimization.general.servercore.ChunkGenerationOwnerLock.lock(level);
                genLock.lock();
                try {
                    bridge.arclight$drainDeferredReschedules();
                } finally {
                    genLock.unlock();
                }
            }
        }
    }
}
