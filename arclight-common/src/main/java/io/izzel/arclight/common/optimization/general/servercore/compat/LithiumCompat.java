package io.izzel.arclight.common.optimization.general.servercore.compat;

/** Lithium 加载探测：其 ChunkStatusTracker 要求主线程。 */
public final class LithiumCompat {

    private static final boolean LOADED;

    static {
        boolean loaded = false;
        try {
            Class.forName("net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker");
            loaded = true;
        } catch (Throwable ignored) {
        }
        LOADED = loaded;
    }

    private LithiumCompat() {
    }

    public static boolean loaded() {
        return LOADED;
    }
}
