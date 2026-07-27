package io.izzel.arclight.common.optimization.general.util;

/**
 * Local re-implementation of the coordinate-packing helpers that HariPlayer's
 * spatial chunk-watching optimization took from {@code io.papermc.paper.util.MCUtil}.
 * <p>
 * We do NOT depend on the paperutil package on purpose: PRTS/Arclight already
 * ships its own classes there, and adding a split-package reference would break
 * module/package sealing. These three methods only pack/unpack chunk coordinates
 * into a single {@code long}, which is trivial to do locally.
 */
public class MCUtil {

    public static long getCoordinateKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int getCoordinateX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int getCoordinateZ(long key) {
        return (int) (key >>> 32);
    }
}
