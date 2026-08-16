/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.entityspatial;

import net.minecraft.core.BlockPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Lazily-built 4x4x4 sub-grid spatial index for one {@code EntitySection}, accelerating pure
 * AABB queries ({@code EntitySection.getEntities(AABB, consumer)}).
 *
 * <p>Each entity is assigned to a single cell by its {@code blockPosition()} low bits (4 blocks
 * per cell). Queries inflate the box by {@link CellMath#QUERY_INFLATE} and scan only covered
 * cells; exact {@code getBoundingBox().intersects} still filters the candidates, so the index is
 * conservative (only extra candidates, never a missed one).
 *
 * <p><b>Order preservation</b> (compat red line): entities get a monotonically increasing
 * insertion sequence; query results are sorted by that sequence before being handed to the
 * consumer. Because {@code ArrayList.remove} is order-preserving, the vanilla per-section order
 * (current {@code allInstances} order) equals the insertion order of the remaining entities, so
 * the indexed output is identical to the vanilla linear scan (order, no duplicates — one cell per
 * entity; self-inclusion is handled at the Level lambda layer and is untouched).
 *
 * <p>All mutation/query methods must be called under the owning section's lock (see
 * {@code EntitySectionMixin_SpatialIndex}).
 */
public final class EntitySpatialIndex<T extends EntityAccess> {

    private final List<T>[] buckets = new List[CellMath.CELL_COUNT];
    private final IdentityHashMap<T, Integer> cellOf = new IdentityHashMap<>();
    private final IdentityHashMap<T, Integer> seqOf = new IdentityHashMap<>();
    private int nextSeq;
    private int size;

    public void add(T entity) {
        BlockPos pos = entity.blockPosition();
        int cell = CellMath.cellId(pos.getX(), pos.getY(), pos.getZ());
        List<T> bucket = this.buckets[cell];
        if (bucket == null) {
            bucket = new ArrayList<>(4);
            this.buckets[cell] = bucket;
        }
        bucket.add(entity);
        this.cellOf.put(entity, cell);
        this.seqOf.put(entity, this.nextSeq++);
        this.size++;
        EntitySpatialIndexStats.setGauge("indexedEntities", this.size);
    }

    /** @return true if the entity was present in the index */
    public boolean remove(T entity) {
        Integer cell = this.cellOf.remove(entity);
        if (cell == null) {
            return false;
        }
        this.buckets[cell].remove(entity);
        this.seqOf.remove(entity);
        this.size--;
        EntitySpatialIndexStats.setGauge("indexedEntities", this.size);
        return true;
    }

    /** Move the entity between cells if its cell changed (position updated by caller). Sequence is kept. */
    public void rebome(T entity) {
        Integer cell = this.cellOf.get(entity);
        if (cell == null) {
            return; // not indexed (should not happen: only called on entities known to the index)
        }
        BlockPos pos = entity.blockPosition();
        int newCell = CellMath.cellId(pos.getX(), pos.getY(), pos.getZ());
        if (cell == newCell) {
            return; // static within the cell -> zero cost for idle high-density sections
        }
        this.buckets[cell].remove(entity);
        List<T> bucket = this.buckets[newCell];
        if (bucket == null) {
            bucket = new ArrayList<>(4);
            this.buckets[newCell] = bucket;
        }
        bucket.add(entity);
        this.cellOf.put(entity, newCell);
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    /** Query covered cells for the (un-inflated) box; must hold the section read lock. */
    public AbortableIterationConsumer.Continuation query(AABB bounds, AbortableIterationConsumer<? super T> consumer) {
        boolean[] xs = CellMath.coveredCells(bounds.minX - CellMath.QUERY_INFLATE, bounds.maxX + CellMath.QUERY_INFLATE);
        boolean[] ys = CellMath.coveredCells(bounds.minY - CellMath.QUERY_INFLATE, bounds.maxY + CellMath.QUERY_INFLATE);
        boolean[] zs = CellMath.coveredCells(bounds.minZ - CellMath.QUERY_INFLATE, bounds.maxZ + CellMath.QUERY_INFLATE);

        ArrayList<T> candidates = new ArrayList<>(this.size);
        for (int x = 0; x < CellMath.CELLS_PER_DIM; x++) {
            if (!xs[x]) {
                continue;
            }
            for (int y = 0; y < CellMath.CELLS_PER_DIM; y++) {
                if (!ys[y]) {
                    continue;
                }
                for (int z = 0; z < CellMath.CELLS_PER_DIM; z++) {
                    if (!zs[z]) {
                        continue;
                    }
                    List<T> bucket = this.buckets[x | (y << 2) | (z << 4)];
                    if (bucket != null) {
                        candidates.addAll(bucket);
                    }
                }
            }
        }
        EntitySpatialIndexStats.add("candidatesScanned", candidates.size());

        // order preservation: insertion sequence == vanilla per-section order
        candidates.sort((a, b) -> Integer.compare(this.seqOf.get(a), this.seqOf.get(b)));

        for (T entity : candidates) {
            if (entity.getBoundingBox().intersects(bounds)
                    && consumer.accept(entity).shouldAbort()) {
                return AbortableIterationConsumer.Continuation.ABORT;
            }
        }
        return AbortableIterationConsumer.Continuation.CONTINUE;
    }
}
