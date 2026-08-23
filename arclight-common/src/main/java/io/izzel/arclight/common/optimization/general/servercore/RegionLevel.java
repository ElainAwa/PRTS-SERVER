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

    // S4 不等宽条带：group -> regionId 映射表。缺省等分时与除法逐位一致；
    // 仅在主线程安全窗内整体替换引用（volatile 写），regionId 读不可变数组内容，无锁无竞态。
    private static volatile int[] groupRegion;

    static void setStripeWidth(int stripeWidth) {
        STRIPE_WIDTH = Math.max(8, Integer.highestOneBit(stripeWidth));
    }

    private final int regionId;

    private RegionLevel(int regionId) {
        this.regionId = regionId;
    }

    /** Region id for a chunk column (table lookup; equal split by default). */
    public static int regionId(int chunkX) {
        int group = Math.floorMod(chunkX, STRIPE_WIDTH);
        return regionIdOfGroup(group);
    }

    /** Region id for a block position. */
    public static int regionId(BlockPos pos) {
        return regionId(pos.getX() >> 4);
    }

    public static int regionId(ChunkPos pos) {
        return regionId(pos.x);
    }

    /** Group -> region lookup. group must already be normalized to [0, STRIPE_WIDTH). */
    static int regionIdOfGroup(int group) {
        int[] table = groupRegion;
        if (table == null) {
            return equalRegionId(group);
        }
        return table[group];
    }

    /** Equal-split fallback / reference implementation, bitwise identical to the legacy division. */
    private static int equalRegionId(int group) {
        return Math.min(regionCount() - 1, group / (STRIPE_WIDTH / regionCount()));
    }

    /** Rebuilds the equal mapping table (startup / rescale / uneven disabled). Main thread only. */
    static synchronized void resetToEqualMapping() {
        int n = Math.max(1, regionCount());
        int[] table = new int[STRIPE_WIDTH];
        for (int g = 0; g < STRIPE_WIDTH; g++) {
            table[g] = equalRegionId(g);
        }
        groupRegion = table;
    }

    /** Installs a rebalanced mapping table (full reference swap, main-thread safety window). */
    static void applyMapping(int[] newTable) {
        groupRegion = newTable;
    }

    /** Current stripe width (for gate checks). */
    public static int stripeWidth() {
        return STRIPE_WIDTH;
    }

    /** Snapshot of the group->region table (defensive copy, may be null pre-init). */
    public static int[] mappingSnapshot() {
        int[] table = groupRegion;
        return table == null ? null : table.clone();
    }

    /** Per-region group widths, computed on demand from the mapping table (no cached state). */
    public static int[] regionWidths() {
        int n = Math.max(1, regionCount());
        int[] widths = new int[n];
        int[] table = groupRegion;
        if (table == null) {
            int perRegion = STRIPE_WIDTH / n;
            for (int i = 0; i < n; i++) {
                widths[i] = perRegion;
            }
            return widths;
        }
        for (int region : table) {
            if (region >= 0 && region < n) {
                widths[region]++;
            }
        }
        return widths;
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
