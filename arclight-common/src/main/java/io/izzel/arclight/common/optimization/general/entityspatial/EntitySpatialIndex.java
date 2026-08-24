/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.entityspatial;

import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Lazily-built 4x4x4 sub-grid spatial index for one {@code EntitySection}, accelerating pure
 * AABB queries ({@code EntitySection.getEntities(AABB, consumer)}) and typed queries
 * ({@code EntitySection.getEntities(EntityTypeTest, ...)}).
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
 * <p><b>Typed queries</b> ({@link #query(EntityTypeTest, AABB, AbortableIterationConsumer, List)}):
 * instead of maintaining a class x cell composite bucket map, we iterate the
 * vanilla per-class collection — obtained via {@code ClassInstanceMultiMap.find(getBaseClass())},
 * which is exact by construction (same lazy list, same order, same IllegalArgumentException on
 * invalid classes) — and skip members whose bounding-box center cell is outside the covered
 * cells. The inflate guarantee makes the skip sound: an entity whose bb intersects the query has
 * its center within {@link CellMath#QUERY_INFLATE} of it, hence inside a covered cell. Skipping
 * costs one cell computation + mask check per class member and saves the {@code intersects} test
 * (and consumer accept) for out-of-range members; it is never worse than the vanilla typed scan,
 * and it keeps the vanilla class-collection order without sorting. The per-cell class buckets of
 * a composite bucket map would only save the instanceof tryCast on foreign candidates — negligible
 * against the maintenance cost they would impose on every add/remove/rebome.
 *
 * <p>All mutation/query methods must be called under the owning section's lock (see
 * {@code EntitySectionMixin_SpatialIndex}).
 */
public final class EntitySpatialIndex<T extends EntityAccess> {

    /** Storage-skip iteration is used when at least this many cells (of 64) are covered. */
    private static final int STORAGE_SKIP_MIN_CELLS = 8;

    /** Plain vanilla loop when at least this many cells are covered (half the section). */
    private static final int STORAGE_SKIP_HIGH_CELLS = 32;

    private final List<T>[] buckets = new List[CellMath.CELL_COUNT];
    private final IdentityHashMap<T, Integer> cellOf = new IdentityHashMap<>();
    private final IdentityHashMap<T, Integer> seqOf = new IdentityHashMap<>();
    private int nextSeq;
    private int size;

    public void add(T entity) {
        int cell = cellOf(entity);
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
        int newCell = cellOf(entity);
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

    /** Cell of an entity, keyed by its bounding-box center (see {@link CellMath#QUERY_INFLATE}). */
    private static int cellOf(EntityAccess entity) {
        AABB bb = entity.getBoundingBox();
        return CellMath.cellId((int) Math.floor(bb.getCenter().x), (int) Math.floor(bb.getCenter().y), (int) Math.floor(bb.getCenter().z));
    }

    /** Query covered cells for the (un-inflated) box; must hold the section read lock. */
    public AbortableIterationConsumer.Continuation query(AABB bounds, AbortableIterationConsumer<? super T> consumer) {
        return query(bounds, consumer, null);
    }

    /**
     * Same as {@link #query(AABB, AbortableIterationConsumer)}, optionally with the section's
     * vanilla {@code allInstances} list. Three tiers, chosen by the number of covered cells:
     * <ul>
     *   <li>{@code >= STORAGE_SKIP_HIGH_CELLS}: the box covers most of the section — the per-member
     *       cell check would be pure overhead, so run the plain vanilla loop (exact vanilla cost).</li>
     *   <li>{@code >= STORAGE_SKIP_MIN_CELLS}: storage-skip iteration — members whose bounding-box
     *       center cell is not covered are skipped (the inflate guarantee makes the skip exact, see
     *       {@link #query(EntityTypeTest, AABB, AbortableIterationConsumer, List)}); vanilla
     *       order, no allocation, no sort.</li>
     *   <li>below: very selective queries — bucket gather + seq sort of the small candidate set.</li>
     * </ul>
     */
    public AbortableIterationConsumer.Continuation query(AABB bounds, AbortableIterationConsumer<? super T> consumer,
                                                         List<? extends T> storage) {
        boolean[] xs = CellMath.coveredCells(bounds.minX - CellMath.QUERY_INFLATE, bounds.maxX + CellMath.QUERY_INFLATE);
        boolean[] ys = CellMath.coveredCells(bounds.minY - CellMath.QUERY_INFLATE, bounds.maxY + CellMath.QUERY_INFLATE);
        boolean[] zs = CellMath.coveredCells(bounds.minZ - CellMath.QUERY_INFLATE, bounds.maxZ + CellMath.QUERY_INFLATE);
        if (storage != null) {
            int covered = coveredCellCount(xs, ys, zs);
            if (covered >= STORAGE_SKIP_HIGH_CELLS) {
                // box covers most of the section: cell checks are overhead, plain vanilla loop
                EntitySpatialIndexStats.increment("vanillaOrderQueries");
                for (int i = 0; i < storage.size(); i++) {
                    T entity = storage.get(i);
                    if (entity.getBoundingBox().intersects(bounds)
                            && consumer.accept(entity).shouldAbort()) {
                        return AbortableIterationConsumer.Continuation.ABORT;
                    }
                }
                return AbortableIterationConsumer.Continuation.CONTINUE;
            }
            if (covered >= STORAGE_SKIP_MIN_CELLS) {
                int skipped = 0;
                boolean aborted = false;
                for (int i = 0; i < storage.size(); i++) {
                    T entity = storage.get(i);
                    if (!isCellCovered(xs, ys, zs, entity)) {
                        skipped++;
                        continue;
                    }
                    if (entity.getBoundingBox().intersects(bounds)
                            && consumer.accept(entity).shouldAbort()) {
                        aborted = true;
                        break;
                    }
                }
                EntitySpatialIndexStats.add("membersSkipped", skipped);
                return aborted ? AbortableIterationConsumer.Continuation.ABORT : AbortableIterationConsumer.Continuation.CONTINUE;
            }
        }

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

    /**
     * Typed query: same inflate/covered-cell rule as
     * {@link #query(AABB, AbortableIterationConsumer)}, but iterating the vanilla per-class
     * collection {@code classCollection} (from the raw class list exposed by the section storage,
     * under the section write lock by the caller) and skipping members whose bounding-box center
     * cell is not covered. The {@code tryCast} filter is applied per member exactly like vanilla
     * ({@code forExactClass} tests need it even inside the class collection), so results, order
     * and abort semantics are byte-identical to the vanilla typed scan; only the {@code intersects}
     * work for out-of-range members is saved.
     */
    @SuppressWarnings("unchecked")
    public <U extends T> AbortableIterationConsumer.Continuation query(EntityTypeTest<T, U> test, AABB bounds,
                                                                      AbortableIterationConsumer<? super U> consumer,
                                                                      List<? extends T> classCollection) {
        boolean[] xs = CellMath.coveredCells(bounds.minX - CellMath.QUERY_INFLATE, bounds.maxX + CellMath.QUERY_INFLATE);
        boolean[] ys = CellMath.coveredCells(bounds.minY - CellMath.QUERY_INFLATE, bounds.maxY + CellMath.QUERY_INFLATE);
        boolean[] zs = CellMath.coveredCells(bounds.minZ - CellMath.QUERY_INFLATE, bounds.maxZ + CellMath.QUERY_INFLATE);

        int scanned = 0;
        int skipped = 0;
        boolean aborted = false;
        for (int i = 0; i < classCollection.size(); i++) {
            T entity = classCollection.get(i);
            scanned++;
            U u = test.tryCast(entity);
            if (u == null) {
                continue;
            }
            if (!isCellCovered(xs, ys, zs, entity)) {
                skipped++;
                continue;
            }
            if (entity.getBoundingBox().intersects(bounds) && consumer.accept(u).shouldAbort()) {
                aborted = true;
                break;
            }
        }
        EntitySpatialIndexStats.add("typedScanned", scanned);
        EntitySpatialIndexStats.add("typedSkipped", skipped);
        return aborted ? AbortableIterationConsumer.Continuation.ABORT : AbortableIterationConsumer.Continuation.CONTINUE;
    }

    /** True if the cell of the entity's bounding-box center is covered by all three axis masks. */
    private static boolean isCellCovered(boolean[] xs, boolean[] ys, boolean[] zs, EntityAccess entity) {
        AABB bb = entity.getBoundingBox();
        int id = CellMath.cellId((int) Math.floor(bb.getCenter().x), (int) Math.floor(bb.getCenter().y), (int) Math.floor(bb.getCenter().z));
        return xs[id & 3] && ys[(id >> 2) & 3] && zs[(id >> 4) & 3];
    }

    /**
     * Number of covered cells (0..64) from the three axis masks. The storage-skip iteration is
     * used when this is at least {@link #STORAGE_SKIP_MIN_CELLS} (12.5% of the section); below
     * that the bucket gather+sort of a small candidate set is cheaper.
     */
    private static int coveredCellCount(boolean[] xs, boolean[] ys, boolean[] zs) {
        int count = 0;
        for (int x = 0; x < CellMath.CELLS_PER_DIM; x++) {
            if (!xs[x]) {
                continue;
            }
            for (int y = 0; y < CellMath.CELLS_PER_DIM; y++) {
                if (!ys[y]) {
                    continue;
                }
                for (int z = 0; z < CellMath.CELLS_PER_DIM; z++) {
                    if (zs[z]) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
