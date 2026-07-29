package io.izzel.arclight.common.optimization.general.chunkwatching;

/** Contract mixed onto {@code net.minecraft.server.level.PlayerMap} (Yarn: PlayerChunkWatchingManager) */
public interface IChunkWatchingManager {
    void setWatchDistance(int watchDistance);

    int getWatchDistance();
}
