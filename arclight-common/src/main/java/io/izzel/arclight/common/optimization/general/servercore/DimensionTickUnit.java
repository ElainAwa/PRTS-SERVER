package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.server.level.ServerLevel;

import java.util.function.BooleanSupplier;

/**
 * Dimension-backed tick unit (P2 behavior, wrapped as a unit in P3 slice 0).
 * Runs the whole {@link ServerLevel#tick} on its worker thread.
 */
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
        this.level.tick(hasTimeLeft);
    }
}
