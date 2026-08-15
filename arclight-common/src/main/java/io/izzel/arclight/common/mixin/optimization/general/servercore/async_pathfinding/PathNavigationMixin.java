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

    @Shadow
    protected double speedModifier;

    @Unique
    private volatile boolean arclight$asyncPending;

    @Unique
    private volatile boolean arclight$pathKeep;

    /** moveTo(null, speed) 在异步在途/饱和时被取消，速度参数只有这里能拿到；
     * 结果应用时若不同步 speedModifier，moveControl 会停留在 WAIT/0 速 → 生物原地罚站。 */
    @Unique
    private double arclight$pendingSpeed = 1.0;

    @Shadow
    protected void trimPath() {
    }

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
    public boolean arclight$isPathKeep() {
        return this.arclight$pathKeep;
    }

    @Override
    public void arclight$markPathKeep() {
        this.arclight$pathKeep = true;
    }

    @Override
    public void arclight$clearPathKeep() {
        this.arclight$pathKeep = false;
    }

    @Override
    public void arclight$applyAsyncResult(Path path) {
        if (path == null) return;
        this.path = path;
        this.arclight$pathKeep = false;
        // 结果应用等价于 moveTo(path, speed) 的后半段：同步速度（否则 moveControl WAIT/0 速，
        // 生物原地罚站）、裁剪起点、同步 targetPos，避免导航状态机误判"已到达"。
        this.speedModifier = this.arclight$pendingSpeed > 0.0 ? this.arclight$pendingSpeed : 1.0;
        this.trimPath();
        BlockPos target = path.getTarget();
        if (target != null) {
            this.targetPos = target;
        }
    }

    @Override
    public PathFinder arclight$createPathFinder(int range) {
        return this.arclight$invokerCreatePathFinder(range);
    }

    // 异步在途或队列饱和跳过时 createPath 返回 null, 原版 moveTo(null) 会清空当前路径——
    // 每 tick 清一次, 结果刚应用就被丢弃 / 饱和期生物原地罚站。两种情况都保留旧路径。
    @Inject(method = "moveTo(Lnet/minecraft/world/level/pathfinder/Path;D)Z",
            at = @At("HEAD"), cancellable = true)
    private void arclight$keepPathWhilePending(Path path, double speedModifier, CallbackInfoReturnable<Boolean> cir) {
        if (path == null && (this.arclight$asyncPending || this.arclight$pathKeep)) {
            this.arclight$pendingSpeed = speedModifier;
            cir.setReturnValue(false);
        }
    }
}
