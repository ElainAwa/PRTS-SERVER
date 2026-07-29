package io.izzel.arclight.common.optimization.general.util;

/** Local re-implementation of the coordinate-packing helpers that Paper-family */
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
