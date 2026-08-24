/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.async_pathfinding.SyncPathfindingGate;
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
        // One-shot callers (PathNavigation.createPath(Set,int), e.g. AcquirePoi job-site
        // claiming) need the synchronous Path return value; run vanilla A* for them.
        if (SyncPathfindingGate.consume()) {
            return;
        }
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
        if (access.arclight$isAsyncPending()) {
            // 在途时直接取消: 原实现放行走 vanilla 同步 A*, 每个在途 tick 都在 worker 上重算一遍
            // (生产 4 秒卡顿来源), 且刚算好的结果被 moveTo(null) 清空。
            cir.setReturnValue(null);
            return;
        }

        long tick = server.getTickCount();
        // Result draining must stay on the thread that owns the entity's region:
        // server thread drains the main queue, region workers drain their own
        // bucket at the next session start (AsyncPathfindingManager.drainRegion).
        if (serverThread) {
            AsyncPathfindingManager.drainIfNeeded(tick);
            // 主线程单目标移动寻路也可异步（配置开启时），
            // 否则被路由到主线程的 villager 每次都在主线程同步跑 A*。
            if (!PRTSFeaturesConfig.mainThreadPathAsync) {
                access.arclight$clearPathKeep();
                return; // vanilla A* on main thread (default)
            }
            // 开启时继续走下方 snapshot + submit 逻辑（主队列结果由 drainIfNeeded 应用）。
        }
        if (!AsyncPathfindingManager.canSubmit()) {
            // 队列饱和：跳过本次寻路（下 tick 重试），同步 A* 回退会拖垮 region worker TPS。
            // 但必须保住当前路径，否则原版 moveTo(null) 每 tick 清一次路径，饱和期生物原地罚站。
            access.arclight$markPathKeep();
            LOGGER.debug("[pf-async] queue full, keeping current path this tick");
            cir.setReturnValue(null);
            return;
        }

        // 提交成功与否都会清掉"保路径"标记：成功时结果会接管路径；失败说明是
        // 极窄的 reserve 竞态，下一 tick 会重新走饱和分支再次标记。
        access.arclight$clearPathKeep();

        // 快照捕获线程 = 拥有该区域状态的线程(主线程 / 维度 worker / 区域 worker):
        // BlockState 是不可变单例, 引用数组拷贝后工作线程完全脱离可变状态。
        int radius = (int) (maxRange + accuracy);
        // 快照垂直范围封顶: A* 垂直探索受跳跃/跌落限制, ±16 足够, 原 ±40 每请求多拷 30 万格。
        ImmutablePathNavigationRegion snapshot = ((PathNavigationRegionAccess) region).arclight$snapshot(mob.getBlockY(), Math.min(radius, 16));
        // 节点预算镜像 vanilla PathNavigation 构造器: floor(followRange*16)。此前误传 radius(≈17),
        // A* 只能搜出 3-4 格路径 → 生物"追到三四格就不动"。
        int nodeBudget = net.minecraft.util.Mth.floor(maxRange * 16);
        PathFinder taskFinder = access.arclight$createPathFinder(nodeBudget);
        int regionId = regionWorker ? RegionTickManager.currentRegion() : -1;
        if (AsyncPathfindingManager.submit(taskFinder, snapshot, mob, targets,
                maxRange, accuracy, depthMultiplier, navigation, tick, regionId, dimensionWorker)) {
            cir.setReturnValue(null);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[pf-async] submitted nav={} mob={} targets={} region={}", navigation, mob.getType(), targets.size(), regionId);
            }
        } else {
            // reservePending 失败的窄竞态：保住路径，下一 tick 重试。
            access.arclight$markPathKeep();
        }
    }
}
