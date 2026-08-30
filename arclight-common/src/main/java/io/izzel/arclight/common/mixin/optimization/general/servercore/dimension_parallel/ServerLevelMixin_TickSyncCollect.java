/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dimension_parallel;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkProviderBridge;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerLevelRegionBlockTickAccess;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BooleanSupplier;

/** 维度 worker 上保留生成驱动，客户端同步链与实体管理收口到主线程 POST。 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_TickSyncCollect {

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void arclight$collectChunkSourceTick(ServerChunkCache chunkSource, BooleanSupplier hasTimeLeft,
                                                 boolean tickPassengers) {
        ServerLevel level = (ServerLevel) (Object) this;
        // 方块刻/跨区 journal 阶段先跑（与 ServerLevelMixin_RegionBlockTick 的
        // regionBlockTickPhase 合并于此：同点 @Redirect 只保留一个，避免 Mixin 冲突跳过）。
        RegionTickManager.runBlockTickPhase(level);
        // 生成驱动留在 worker 立即执行；同步链仍收口主线程 POST
        if (DimensionTickManager.isDimensionTickThread()) {
            // 与主线程强制加载泵共用生成属主锁
            java.util.concurrent.locks.ReentrantLock genLock =
                    io.izzel.arclight.common.optimization.general.servercore.ChunkGenerationOwnerLock.lock(level);
            genLock.lock();
            try {
                ((ServerChunkProviderBridge) (Object) chunkSource).bridge$tickDistanceManager();
            } finally {
                genLock.unlock();
            }
            DimensionTickManager.collectPostSync(level, () -> chunkSource.tick(hasTimeLeft, tickPassengers));
        } else {
            chunkSource.tick(hasTimeLeft, tickPassengers);
        }
    }

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;tick()V"))
    private void arclight$collectEntityManagerTick(PersistentEntitySectionManager<?> manager) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (DimensionTickManager.isDimensionTickThread()) {
            DimensionTickManager.collectPostSync(level, manager::tick);
        } else {
            manager.tick();
        }
    }

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;runBlockEvents()V"))
    private void arclight$collectBlockEvents(ServerLevel level) {
        if (DimensionTickManager.isDimensionTickThread()) {
            // 活塞形状更新链在 worker 上读 BE 恒 null，收口主线程 POST
            RegionTickManager.queueMainThreadBlockEvents(level,
                    ((ServerLevelRegionBlockTickAccess) level)::arclight$runBlockEvents);
        } else {
            ((ServerLevelRegionBlockTickAccess) level).arclight$runBlockEvents();
        }
    }
}
