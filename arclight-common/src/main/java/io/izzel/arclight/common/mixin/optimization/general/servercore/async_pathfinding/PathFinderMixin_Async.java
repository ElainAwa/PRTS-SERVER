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
 * PRTS async pathfinding entry point (P1 experiment, AI-created).
 * When {@link PRTSFeaturesConfig#parallelPathfindingAsync}
 * is enabled and the call
 * happens on the server thread (or on a P2 dimension worker thread), the A*
 * computation of
 * {@link PathFinder#findPath(PathNavigationRegion, Mob, Set, float, int, float)}
 * is submitted to {@link AsyncPathfindingManager} and the caller receives null
 * (the navigation keeps moving along its previous path until the async result
 * is applied at the next tick boundary).
 *
 * A per-navigation pending flag prevents submission snowballing: while a result
 * is pending, subsequent calls fall through to the synchronous vanilla path.
 *
 * P1 x P2 stacking: when dimension parallelism (P2) is active, dimension tick
 * happens on {@code PRTS-DimensionTick-*} worker threads instead of the server
 * thread. Those workers own their dimension's state (same semantics as the
 * vanilla single-thread model, see docs/parallel-phase2-dimension-parallelism-v01.md),
 * so submission is also allowed there. Result draining stays strictly on the
 * server thread (MinecraftServerMixin_AsyncDrain) to avoid cross-thread
 * application of navigation state.
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
        LOGGER.info("[pf-async] entered thread={} mob={} feature={}", Thread.currentThread().getName(),
                mob != null ? mob.getType() : "null",
                PRTSFeaturesConfig.parallelPathfindingAsync);
        if (!PRTSFeaturesConfig.parallelPathfindingAsync) {
            LOGGER.info("[pf-async] feature disabled");
            return;
        }
        MinecraftServer server = mob.level().getServer();
        if (server == null) {
            return;
        }
        boolean serverThread = server.isSameThread();
        boolean dimensionWorker = DimensionTickManager.isDimensionTickThread();
        boolean regionWorker = RegionTickManager.isRegionTickThread();
        if (!serverThread && !dimensionWorker && !regionWorker) {
            LOGGER.info("[pf-async] not server thread: {}", Thread.currentThread().getName());
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
            LOGGER.info("[pf-async] queue full, fallback sync");
            return;
        }

        // 快照捕获线程 = 拥有该区域状态的线程(主线程 P1 / 维度 worker P2 / 区域 worker P3):
        // BlockState 是不可变单例, 引用数组拷贝后工作线程完全脱离可变状态。
        int radius = (int) (maxRange + accuracy);
        ImmutablePathNavigationRegion snapshot = ((PathNavigationRegionAccess) region).arclight$snapshot(mob.getBlockY(), radius);
        PathFinder taskFinder = access.arclight$createPathFinder(radius);
        int regionId = regionWorker ? RegionTickManager.currentRegion() : -1;
        if (AsyncPathfindingManager.submit(taskFinder, snapshot, mob, targets,
                maxRange, accuracy, depthMultiplier, navigation, tick, regionId)) {
            access.arclight$markAsyncPending();
            cir.setReturnValue(null);
            LOGGER.info("[pf-async] submitted nav={} mob={} targets={} region={}", navigation, mob.getType(), targets.size(), regionId);
        }
    }
}
