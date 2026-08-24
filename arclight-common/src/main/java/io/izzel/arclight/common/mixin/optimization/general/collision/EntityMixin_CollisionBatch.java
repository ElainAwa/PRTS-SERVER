/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.collision;

import com.google.common.collect.ImmutableList;
import io.izzel.arclight.common.bridge.optimization.IEntityCollisionBatch;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.optimization.general.collision.CollisionBatch;
import io.izzel.arclight.common.optimization.general.collision.CollisionBatchStats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Collision shape collection batching: cache the block-shape collection
 * of the first {@code collectColliders} call inside one {@code collide} frame and serve the
 * step-up branch from it, fetching only the vertical delta cap above the cached region.
 *
 * <p>Vanilla 1.21.1 {@code collideBoundingBox} collects the swept region once and clips per
 * axis; the step-up branch of {@code collide} then collects the raised region a second time —
 * a full re-walk of the same blocks plus the step height, executed on nearly every ground move
 * of stepping mobs. The batch turns that second full collection into one small cap fetch.
 *
 * <p>Compatibility guarantees:
 * <ul>
 *   <li><b>Identical results</b>: the merged list is exactly the vanilla second collection's
 *       shape set (cache = overlap, cap = fresh {@code getBlockCollisions}); per-axis clips and
 *       step-height candidates are computed from the same shapes, so the collision outcome is
 *       unchanged.</li>
 *   <li><b>No stale state</b>: the batch lives only inside one {@code collide} frame on the
 *       owning entity; re-entrant frames refresh it, outside callers of the static
 *       {@code collideBoundingBox} see no batch at all.</li>
 *   <li><b>Yield</b>: Lithium/Canary/Radium rewrite the same collide path; we step aside when
 *       they are present.</li>
 * </ul>
 */
@Mixin(Entity.class)
@LoadIfMod(modid = {ModIds.LITHIUM, ModIds.CANARY, ModIds.RADIUM}, condition = LoadIfMod.ModCondition.ABSENT)
public abstract class EntityMixin_CollisionBatch implements IEntityCollisionBatch {

    @Unique
    private CollisionBatch prts$collisionBatch;

    @Override
    public CollisionBatch arclight$getCollisionBatch() {
        return this.prts$collisionBatch;
    }

    @Override
    public void arclight$setCollisionBatch(CollisionBatch batch) {
        this.prts$collisionBatch = batch;
    }

    // ---- frame lifecycle: the batch exists only inside Entity.collide ----

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"))
    private void prts$batchInit(Vec3 vec3, CallbackInfoReturnable<Vec3> cir) {
        if (!PRTSFeaturesConfig.collisionBatchEnabled) {
            return;
        }
        this.prts$collisionBatch = new CollisionBatch(((Entity) (Object) this).level());
    }

    @Inject(method = "collide", at = @At("RETURN"))
    private void prts$batchClear(CallbackInfoReturnable<Vec3> cir) {
        this.prts$collisionBatch = null;
    }

    // ---- collection: serve from cache / extend incrementally / fall back to vanilla ----

    @Redirect(method = {"collide", "collideBoundingBox"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;collectColliders(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<VoxelShape> prts$batchCollect(Entity entity, Level level,
                                                      List<VoxelShape> entityShapes, AABB region) {
        CollisionBatch batch = entity instanceof IEntityCollisionBatch holder
                ? holder.arclight$getCollisionBatch() : null;
        if (batch == null || batch.level != level) {
            return vanillaCollect(entity, level, entityShapes, region);
        }
        if (batch.region != null) {
            if (contains(batch.region, region)) {
                CollisionBatchStats.increment("reusedShapes");
                return batch.shapes;
            }
            if (isVerticalCapExtension(batch.region, region)) {
                // step-up: only the cap above the cached region is new
                AABB cap = new AABB(region.minX, batch.region.maxY, region.minZ,
                        region.maxX, region.maxY, region.maxZ);
                ImmutableList.Builder<VoxelShape> merged = ImmutableList.builder();
                merged.addAll(batch.shapes);
                merged.addAll(level.getBlockCollisions(entity, cap));
                batch.shapes = merged.build();
                batch.region = region;
                CollisionBatchStats.increment("incrementalFetches");
                return batch.shapes;
            }
        }
        // cache miss (re-entrant frame, different region shape): refresh
        batch.region = region;
        batch.shapes = vanillaCollect(entity, level, entityShapes, region);
        CollisionBatchStats.increment("fullFetches");
        return batch.shapes;
    }

    /** Exact replica of the vanilla {@code collectColliders} body. */
    @Unique
    private static List<VoxelShape> vanillaCollect(Entity entity, Level level,
                                                   List<VoxelShape> entityShapes, AABB region) {
        ImmutableList.Builder<VoxelShape> builder = ImmutableList.builder();
        if (!entityShapes.isEmpty()) {
            builder.addAll(entityShapes);
        }
        builder.addAll(level.getBlockCollisions(entity, region));
        return builder.build();
    }

    /** {@code outer.contains(inner)} with a small epsilon for float noise on shared edges. */
    @Unique
    private static boolean contains(AABB outer, AABB inner) {
        return inner.minX >= outer.minX - EPS && inner.minY >= outer.minY - EPS
                && inner.minZ >= outer.minZ - EPS && inner.maxX <= outer.maxX + EPS
                && inner.maxY <= outer.maxY + EPS && inner.maxZ <= outer.maxZ + EPS;
    }

    /** True when {@code extended} shares the horizontal footprint of {@code base} and reaches
     * strictly higher (the step-up request), so the diff is a single cap box. */
    @Unique
    private static boolean isVerticalCapExtension(AABB base, AABB extended) {
        return Math.abs(extended.minX - base.minX) <= EPS && Math.abs(extended.maxX - base.maxX) <= EPS
                && Math.abs(extended.minZ - base.minZ) <= EPS && Math.abs(extended.maxZ - base.maxZ) <= EPS
                && extended.minY >= base.minY - EPS && extended.maxY > base.maxY + EPS;
    }

    @Unique
    private static final double EPS = 1.0E-4D;
}
