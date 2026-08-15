/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore.ownership;

import net.minecraft.core.BlockPos;

/**
 * Thrown by {@link WorldAccessGuard} in {@link ThreadPolicy#ENFORCE} mode when
 * a region worker touches world state that only the main thread may access.
 *
 * <p>The entity-tick wrapper in RegionTickManager catches this exception so a
 * single misbehaving mod aborts only its own tick, never the parallel session.
 */
public class AccessViolation extends RuntimeException {

    private final String ownerClassName;
    private final WorldAccessGuard.AccessKind kind;
    private final BlockPos pos;

    public AccessViolation(String ownerClassName, WorldAccessGuard.AccessKind kind, BlockPos pos) {
        super("Worker world-access violation: " + kind + " by " + ownerClassName + " at " + pos);
        this.ownerClassName = ownerClassName;
        this.kind = kind;
        this.pos = pos;
    }

    public String ownerClassName() {
        return this.ownerClassName;
    }

    public WorldAccessGuard.AccessKind kind() {
        return this.kind;
    }

    public BlockPos pos() {
        return this.pos;
    }
}
