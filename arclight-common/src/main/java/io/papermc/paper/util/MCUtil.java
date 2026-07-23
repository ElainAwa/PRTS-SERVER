/*
 * Licensed under https://github.com/PaperMC/Paper/blob/master/licenses/MIT.md
 */

package io.papermc.paper.util;

import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

public class MCUtil {

    private static final java.lang.ref.Cleaner cleaner = java.lang.ref.Cleaner.create();

    // 还原自 Luminara/Arclight 原版 MCUtil（阶段2 误覆盖为 HariPlayer 精简版时丢失）。
    // 供 com.destroystokyo.paper.util.pooled.PooledObjects 注册资源清理回调。
    // 注意：Cleaner.Cleanable 并非 Runnable，需包装为 Runnable 返回以匹配调用方签名。
    public static <T> Runnable registerCleaner(Object holder, T resource, Consumer<T> releaser) {
        final java.lang.ref.Cleaner.Cleanable cleanable = cleaner.register(holder, () -> releaser.accept(resource));
        return cleanable::clean;
    }

    public static long getCoordinateKey(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }

    public static long getCoordinateKey(final ChunkPos pair) {
        return ((long)pair.z << 32) | (pair.x & 0xFFFFFFFFL);
    }

    public static int getCoordinateX(final long key) {
        return (int)key;
    }

    public static int getCoordinateZ(final long key) {
        return (int)(key >>> 32);
    }

}
