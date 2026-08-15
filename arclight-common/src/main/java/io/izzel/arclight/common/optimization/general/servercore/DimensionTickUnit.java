/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ServerLevel;

import java.util.function.BooleanSupplier;

/** Dimension-backed tick unit: runs the whole {@link ServerLevel#tick} on its worker thread. */
public final class DimensionTickUnit implements ParallelTickUnit {

    private final ServerLevel level;

    public DimensionTickUnit(ServerLevel level) {
        this.level = level;
    }

    @Override
    public String name() {
        return "dim:" + this.level.dimension().location();
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Override
    public void tick(BooleanSupplier hasTimeLeft) {
        // 本维度 worker 提交的异步寻路结果必须由本 worker 应用，先 drain 再 tick，
        // 否则主线程应用会与本维度实体 tick 并发写 PathNavigation。
        AsyncPathfindingManager.drainDimension(this.level, this.level.getServer().getTickCount());
        this.level.tick(hasTimeLeft);
    }
}
