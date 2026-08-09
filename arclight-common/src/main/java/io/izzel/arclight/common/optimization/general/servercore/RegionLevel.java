/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * PRTS region view of a dimension level (P3 slice 1, AI-created).
 *
 * <p>The overworld is partitioned into a configurable number of regions
 * (docs/parallel-phase3-region-parallelism-v11.md). Chunk columns are grouped
 * in stripes of {@value #STRIPE_WIDTH} chunks and each stripe is split evenly
 * between {@code regionCount()} regions (2/4/8). The authoritative set is
 * implicit and immutable for the whole run (the "fixed region definition"
 * review requirement), so an entity belongs to exactly one region at all
 * times. Region boundaries are sparse (one boundary line every
 * {@value #STRIPE_WIDTH} / regionCount columns) to keep cross-region
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
        return Math.min(regionCount() - 1, group / (STRIPE_WIDTH / regionCount()));
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

    /** Number of regions for the current configuration. */
    public static int regionCount() {
        return RegionTickManager.regionCount();
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
