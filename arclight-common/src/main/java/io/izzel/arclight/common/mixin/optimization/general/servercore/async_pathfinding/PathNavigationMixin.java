/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.async_pathfinding;

import io.izzel.arclight.common.optimization.general.servercore.PathNavigationAccess;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * PRTS async pathfinding state (P1 experiment, AI-created).
 * Injects async-pending state and a private PathFinder factory into PathNavigation,
 * implemented through {@link PathNavigationAccess} so the optimization package can
 * interact with it without a compile-time dependency on this mixin.
 */
@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin implements PathNavigationAccess {

    @Shadow
    protected Path path;

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
    }

    @Override
    public PathFinder arclight$createPathFinder(int range) {
        return this.arclight$invokerCreatePathFinder(range);
    }
}
