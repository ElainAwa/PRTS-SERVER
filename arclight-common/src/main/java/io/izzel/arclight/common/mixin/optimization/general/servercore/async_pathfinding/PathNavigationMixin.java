/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.PathNavigationAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Async pathfinding state: injects async-pending state and a private PathFinder
 * factory into PathNavigation, implemented through {@link PathNavigationAccess} so
 * the optimization package can interact with it without a compile-time dependency
 * on this mixin.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin implements PathNavigationAccess {

    @Shadow
    protected Path path;

    @Shadow
    private BlockPos targetPos;

    @Unique
    private volatile boolean arclight$asyncPending;

    @Invoker("createPathFinder")
    protected abstract PathFinder arclight$invokerCreatePathFinder(int range);

    @Override
    public boolean arclight$isAsyncPending() {
        return this.arclight$asyncPending;
    }

    @Override
    public void arclight$markAsyncPending() {
        this.arclight$asyncPending = true;
    }

    @Override
    public void arclight$clearAsyncPending() {
        this.arclight$asyncPending = false;
    }

    @Override
    public void arclight$applyAsyncResult(Path path) {
        if (path == null) return;
        this.path = path;
        // 镜像 vanilla createPath: 同步 targetPos, 否则导航状态机用旧目标误判"已到达"。
        BlockPos target = path.getTarget();
        if (target != null) {
            this.targetPos = target;
        }
    }

    @Override
    public PathFinder arclight$createPathFinder(int range) {
        return this.arclight$invokerCreatePathFinder(range);
    }

    // 异步在途时 createPath 返回 null, 原版 moveTo(null) 会清空当前路径——每 tick 清一次,
    // 结果刚应用就被丢弃, 生物永远无法沿路走。在途时保留旧路径。
    @Inject(method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void arclight$keepPathWhilePending(Path path, double speedModifier, CallbackInfoReturnable<Boolean> cir) {
        if (path == null && this.arclight$asyncPending) {
            cir.setReturnValue(false);
        }
    }
}
