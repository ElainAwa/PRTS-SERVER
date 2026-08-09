/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Bridge interface implemented by PathNavigationMixin.
 * Lets the optimization package interact with async-pending state injected
 * into PathNavigation without compile-time dependency on the mixin class.
 */
public interface PathNavigationAccess {

    boolean arclight$isAsyncPending();

    void arclight$markAsyncPending();

    void arclight$clearAsyncPending();

    void arclight$applyAsyncResult(Path path);

    /** Creates a private PathFinder instance (main thread only) for an async task. */
    PathFinder arclight$createPathFinder(int range);
}
