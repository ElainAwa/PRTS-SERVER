package io.izzel.arclight.common.optimization.general.chunkwatching;

/**
 * Contract mixed onto {@code net.minecraft.server.level.PlayerMap} (Yarn: PlayerChunkWatchingManager)
 * so that {@code ChunkMap} can push view-distance changes into the spatial index without reaching into
 * mixin-private state. The mixin class implements this interface, so at runtime every PlayerMap instance
 * is assignable to it.
 */
public interface IChunkWatchingManager {
    void setWatchDistance(int watchDistance);

    int getWatchDistance();
}
