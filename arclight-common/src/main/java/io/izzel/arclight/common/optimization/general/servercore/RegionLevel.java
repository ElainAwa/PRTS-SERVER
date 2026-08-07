package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * PRTS region view of a dimension level (P3 slice 1, AI-created).
 *
 * <p>Slice 1 uses a static stripe partition: chunk columns are grouped in
 * stripes of {@value #STRIPE_WIDTH} chunks and each stripe is halved between
 * the two regions. The authoritative set is implicit and immutable for the
 * whole run (the "fixed region definition" review requirement), so an entity
 * belongs to exactly one region at all times. Region boundaries are sparse
 * (one boundary line every {@value #STRIPE_WIDTH} columns) to keep cross-region
 * interaction low.</p>
 *
 * <p>Discipline helpers (review §4.1): {@link #isAuthoritative} gates every
 * region-local world access; callers assert it in debug builds before touching
 * shared chunk data. Cross-region access must go through the router layer
 * (slice 3); until then the entity tick path only reads blocks in its own
 * region's columns.</p>
 */
public final class RegionLevel {

    public static final int DEFAULT_REGION_COUNT = 2;
    static final int STRIPE_WIDTH = 8;

    private final int regionId;

    private RegionLevel(int regionId) {
        this.regionId = regionId;
    }

    /** Region id for a chunk column under the static stripe partition. */
    public static int regionId(int chunkX) {
        int group = Math.floorMod(chunkX, STRIPE_WIDTH);
        return group >= STRIPE_WIDTH / DEFAULT_REGION_COUNT ? 1 : 0;
    }

    /** Region id for a block position. */
    public static int regionId(BlockPos pos) {
        return regionId(pos.getX() >> 4);
    }

    public static int regionId(ChunkPos pos) {
        return regionId(pos.x);
    }

    /** True if the chunk column is authoritative for the given region. */
    public static boolean isAuthoritative(int chunkX, int regionId) {
        return regionId(chunkX) == regionId;
    }

    public static boolean isAuthoritative(BlockPos pos, int regionId) {
        return regionId(pos) == regionId;
    }

    public int regionId() {
        return this.regionId;
    }

    public boolean isAuthoritative(BlockPos pos) {
        return isAuthoritative(pos, this.regionId);
    }

    public boolean isAuthoritative(ChunkPos pos) {
        return isAuthoritative(pos.x, this.regionId);
    }
}
