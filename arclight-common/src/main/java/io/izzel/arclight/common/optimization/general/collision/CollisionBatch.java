/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.collision;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * One {@code Entity.collide} frame's worth of pre-collected collision shapes (audit doc
 * §阶段5·5.2).
 *
 * <p>Vanilla 1.21.1 already collects block shapes once ({@code collideBoundingBox} →
 * {@code collectColliders}) and clips per axis, but the step-up branch of {@code collide}
 * runs {@code collectColliders} <em>again</em> over the raised region — a second full walk of
 * the swept area plus the step height. This batch caches the first collection and lets the
 * step-up branch fetch only the delta cap above it; the merged list contains exactly the same
 * shape set as the vanilla second collection (the overlap comes from the cache, the cap from a
 * fresh {@code getBlockCollisions}), so the per-axis clip results are identical.
 *
 * <p>Semantics-neutral: the entity-shape list is passed through untouched, {@code VoxelShape}
 * results are reused verbatim, and every fast path falls back to the vanilla collection when the
 * cached region does not cover the request. Never-read entities (no {@code collide} frame) and
 * re-entrant frames degrade to vanilla.
 */
public final class CollisionBatch {

    /** The world the shapes were collected from; mismatches invalidate the cache. */
    public final Level level;

    /** Union of collected regions (may be {@code null} before the first collection). */
    public AABB region;

    /** entityShapes + block shapes of {@link #region} (superset of any contained request). */
    public List<VoxelShape> shapes;

    public CollisionBatch(Level level) {
        this.level = level;
    }
}
