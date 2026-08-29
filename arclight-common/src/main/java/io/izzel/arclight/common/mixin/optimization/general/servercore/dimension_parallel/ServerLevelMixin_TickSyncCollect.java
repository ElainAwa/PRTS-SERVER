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

/**
 * Keeps the client delta-sync chain on the main thread while the dimension
 * worker continues the compute skeleton. {@code ServerChunkCache.tick} (chunk
 * loading/unloading, random ticks, {@code ChunkMap.tick} entity tracking
 * broadcasts) and {@code PersistentEntitySectionManager.tick} (entity storage
 * management) are deferred to the POST phase of {@link DimensionTickManager}
 * when invoked on a dimension worker; the worker thread never runs them.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_TickSyncCollect {

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void arclight$collectChunkSourceTick(ServerChunkCache chunkSource, BooleanSupplier hasTimeLeft,
                                                 boolean tickPassengers) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (DimensionTickManager.isDimensionTickThread()) {
            // 生成驱动不能延迟：runDistanceManagerUpdates（含 runGenerationTasks → M2 任务提交、
            // 玩家 ticket/区块需求更新）必须在维度 worker 上立即执行，否则生成驱动每 tick 只跑
            // 一次且与玩家需求串行 → M2 worker 全空闲 + 区块加载跟不上（实测根因）。
            // 广播链（tickChunks 的 ChunkMap.tick 发包 + 实体管理器）仍收集到 POST 主线程，
            // 保持 93f00d9 的 Create 客户端同步修复语义。
            ((ServerChunkProviderBridge) (Object) chunkSource).bridge$tickDistanceManager();
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
            // 活塞 triggerEvent（moveBlocks）走 runBlockEvents；其形状更新链在
            // worker 上读 MovingPistonBlock BE 恒 null → 移动链中断/碰撞箱残留，
            // 排主线程 POST 执行（保留 vanilla 事件顺序与 reschedule 语义）。
            RegionTickManager.queueMainThreadBlockEvents(level,
                    ((ServerLevelRegionBlockTickAccess) level)::arclight$runBlockEvents);
        } else {
            ((ServerLevelRegionBlockTickAccess) level).arclight$runBlockEvents();
        }
    }
}
