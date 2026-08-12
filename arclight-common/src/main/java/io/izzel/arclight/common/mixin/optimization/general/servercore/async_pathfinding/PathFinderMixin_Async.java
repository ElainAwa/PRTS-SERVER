/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.AsyncPathfindingManager;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import io.izzel.arclight.common.optimization.general.servercore.ImmutablePathNavigationRegion;
import io.izzel.arclight.common.optimization.general.servercore.PathNavigationAccess;
import io.izzel.arclight.common.optimization.general.servercore.PathNavigationRegionAccess;
import io.izzel.arclight.common.optimization.general.servercore.RegionTickManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Async pathfinding entry point: when enabled and called on a thread owning the
 * entity's region state, submits {@link PathFinder#findPath} to
 * {@link AsyncPathfindingManager} and returns null (the navigation keeps its
 * previous path until the result is applied next tick).
 */
@Mixin(PathFinder.class)
public abstract class PathFinderMixin_Async {

    private static final Logger LOGGER = LogManager.getLogger("Arclight");

    @Inject(
            method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void arclight$asyncPathfind(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
                                        float maxRange, int accuracy, float depthMultiplier,
                                        CallbackInfoReturnable<Path> cir) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[pf-async] entered thread={} mob={} feature={}", Thread.currentThread().getName(),
                    mob != null ? mob.getType() : "null",
                    PRTSFeaturesConfig.parallelPathfindingAsync);
        }
        if (!PRTSFeaturesConfig.parallelPathfindingAsync) {
            LOGGER.debug("[pf-async] feature disabled");
            return;
        }
        MinecraftServer server = mob.level().getServer();
        if (server == null) {
            return;
        }
        boolean serverThread = server.isSameThread();
        boolean dimensionWorker = DimensionTickManager.isDimensionTickThread();
        boolean regionWorker = RegionTickManager.isRegionWorker();
        if (!serverThread && !dimensionWorker && !regionWorker) {
            LOGGER.debug("[pf-async] not server thread: {}", Thread.currentThread().getName());
            return;
        }
        PathNavigation navigation = mob.getNavigation();
        if (navigation == null) return;
        PathNavigationAccess access = (PathNavigationAccess) navigation;
        if (access.arclight$isAsyncPending()) return;

        long tick = server.getTickCount();
        // Result draining must stay on the thread that owns the entity's region:
        // server thread drains the main queue, region workers drain their own
        // bucket at the next session start (AsyncPathfindingManager.drainRegion).
        if (serverThread) {
            AsyncPathfindingManager.drainIfNeeded(tick);
        }
        if (!AsyncPathfindingManager.canSubmit()) {
            LOGGER.debug("[pf-async] queue full, fallback sync");
            return;
        }

        // 快照捕获线程 = 拥有该区域状态的线程(主线程 / 维度 worker / 区域 worker):
        // BlockState 是不可变单例, 引用数组拷贝后工作线程完全脱离可变状态。
        int radius = (int) (maxRange + accuracy);
        ImmutablePathNavigationRegion snapshot = ((PathNavigationRegionAccess) region).arclight$snapshot(mob.getBlockY(), radius);
        PathFinder taskFinder = access.arclight$createPathFinder(radius);
        int regionId = regionWorker ? RegionTickManager.currentRegion() : -1;
        if (AsyncPathfindingManager.submit(taskFinder, snapshot, mob, targets,
                maxRange, accuracy, depthMultiplier, navigation, tick, regionId)) {
            cir.setReturnValue(null);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[pf-async] submitted nav={} mob={} targets={} region={}", navigation, mob.getType(), targets.size(), regionId);
            }
        }
    }
}
