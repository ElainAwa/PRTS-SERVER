/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.general.entityspatial;

/**
 * Pure cell math for the 4x4x4 sub-grid index (JDK-only, unit-testable standalone).
 *
 * <p>A 16x16x16 section is split into 4x4x4 cells of 4 blocks; cell id = {@code x | y<<2 | z<<4}
 * with {@code x = (blockX & 15) >> 2} etc. {@code & 15} on a negative block coordinate equals
 * {@code floorMod(x, 16)} because 16 is a power of two, so negative coordinates work unchanged.
 */
public final class CellMath {

    public static final int CELL_SIZE = 4;
    public static final int CELLS_PER_DIM = 4;
    public static final int CELL_COUNT = 64;

    /** Query inflation in blocks: any entity whose AABB intersects the query is at most
     *  (max AABB radius ~4) blocks from the query box; 8 gives a 2x safety margin. */
    public static final double QUERY_INFLATE = 8.0;

    private CellMath() {
    }

    /** Cell id from a block position (world coords). */
    public static int cellId(int bx, int by, int bz) {
        return ((bx & 15) >> 2) | (((by & 15) >> 2) << 2) | (((bz & 15) >> 2) << 4);
    }

    /** Mark the covered cells along one axis for a [min, max] block range (already inflated by the caller).
     *  Returns a 4-slot boolean mask; up to 4 cells can be covered (wrap across the section boundary). */
    public static boolean[] coveredCells(double min, double max) {
        boolean[] out = new boolean[CELLS_PER_DIM];
        int minCell = (int) Math.floor(min / CELL_SIZE);
        int maxCell = (int) Math.floor(max / CELL_SIZE);
        for (int c = minCell; c <= maxCell; c++) {
            out[c & 3] = true;
        }
        return out;
    }
}
