/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import io.izzel.arclight.common.bridge.core.world.server.ServerChunkCacheRegionBridge;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 异步传送门:PRTS worker 线程上目标维度区块未 FULL 时,提交异步加载需求并延后一 tick
 * (清冷却保证重试),避免 worker 同步等目标区块生成导致的冻结/虚空传送;主线程路径不动。
 * 仅处理原版维度对(下界/主世界/末地);模组传送门不干预。默认关。
 */
@Mixin(PortalProcessor.class)
public abstract class PortalProcessorMixin_Async {

    private static boolean prts$onWorkerThread() {
        return DimensionTickManager.isDimensionTickThread() || RegionTickManager.isRegionWorker();
    }

    private static ServerLevel prts$guessTargetLevel(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dim = level.dimension();
        if (dim == Level.NETHER) {
            return server.overworld();
        }
        if (dim == Level.OVERWORLD) {
            return server.getLevel(Level.NETHER);
        }
        if (dim == Level.END) {
            return server.overworld();
        }
        return null;
    }

    private static boolean prts$targetChunkReady(ServerLevel target, BlockPos pos) {
        ServerChunkCache cache = target.getChunkSource();
        return cache.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Inject(method = "getPortalDestination",
        at = @At("HEAD"), cancellable = true)
    private void arclight$deferPortalOnWorker(ServerLevel level, Entity entity,
                                              CallbackInfoReturnable<Optional<DimensionTransition>> cir) {
        if (!PRTSFeaturesConfig.portalAsync || !prts$onWorkerThread()) {
            return;
        }
        ServerLevel target = prts$guessTargetLevel(level);
        if (target == null) {
            return; // 模组传送门:不干预
        }
        double scale = DimensionType.getTeleportationScale(level.dimensionType(), target.dimensionType());
        BlockPos targetPos = target.getWorldBorder()
                .clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        if (prts$targetChunkReady(target, targetPos)) {
            return;
        }
        // 提交异步加载需求 + 清冷却,延后一 tick 重试(目标区块 FULL 后走原版路径)。
        ((ServerChunkCacheRegionBridge) (Object) target.getChunkSource())
                .arclight$submitChunkDemand(targetPos.getX() >> 4, targetPos.getZ() >> 4);
        entity.setPortalCooldown(0);
        cir.setReturnValue(Optional.empty());
    }
}
