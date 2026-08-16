/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Region view of a dimension level. The overworld is partitioned into a fixed
 * number of regions: chunk columns are grouped in stripes of {@value #STRIPE_WIDTH}
 * chunks and each stripe is split evenly between {@code regionCount()} regions
 * (2/4/8). {@link #isAuthoritative} gates region-local world access.
 */
public final class RegionLevel {

    /** Initial placeholder before ensureConfigured() applies the configured count. */
    public static final int INITIAL_REGION_COUNT = 2;
    /** Chunk-column stripe width. 8 for N<=8, grows to N for N=16 (default behavior unchanged). */
    static volatile int STRIPE_WIDTH = 8;

    static void setStripeWidth(int stripeWidth) {
        STRIPE_WIDTH = Math.max(8, Integer.highestOneBit(stripeWidth));
    }

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
