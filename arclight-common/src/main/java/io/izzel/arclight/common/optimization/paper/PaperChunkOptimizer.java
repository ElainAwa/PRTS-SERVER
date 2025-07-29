package io.izzel.arclight.common.optimization.paper;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaperChunkOptimizer {
    private static final Map<ChunkPos, Long> chunkAccessTimes = new ConcurrentHashMap<>();

    public static void optimizeWorldCreationChunks(ServerLevel level) {
        var worldConfig = ArclightConfig.spec().getOptimization().getWorldCreation();
        var chunkConfig = ArclightConfig.spec().getOptimization().getChunkOptimization();

        if (!worldConfig.isFastWorldCreation() || !chunkConfig.isOptimizeChunkLoading()) {
            return;
        }

        if (!worldConfig.isSkipSpawnChunkLoading()) {
            var spawnPos = level.getSharedSpawnPos();
            var spawnChunk = new ChunkPos(spawnPos);
            int radius = worldConfig.getSpawnAreaRadius();

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    ChunkPos pos = new ChunkPos(spawnChunk.x + x, spawnChunk.z + z);
                    markChunkAccessed(pos);
                }
            }


        }
    }


    public static void markChunkAccessed(ChunkPos pos) {
        chunkAccessTimes.put(pos, System.currentTimeMillis());
    }


    public static boolean isChunkActive(ChunkPos pos) {
        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();
        Long lastAccess = chunkAccessTimes.get(pos);

        if (lastAccess == null) return false;

        long threshold = config.getChunkUnloadDelay() * 1000L;
        return (System.currentTimeMillis() - lastAccess) < threshold;
    }


    public static void optimizeChunkUnloading(ServerLevel level) {
        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();

        if (!config.isAggressiveChunkUnloading()) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long unloadThreshold = config.getChunkUnloadDelay() * 1000L;

        chunkAccessTimes.entrySet().removeIf(entry -> {
            if (currentTime - entry.getValue() > unloadThreshold) {
                if (level.getChunkSource().hasChunk(entry.getKey().x, entry.getKey().z)) {
                    level.getChunkSource().tick(() -> true, true);
                }
                return true;
            }
            return false;
        });
    }

    public static void performPaperChunkGC(ServerLevel level) {
        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();
        level.getChunkSource().tick(() -> true, true);
    }

    public static void clearChunkData() {
        chunkAccessTimes.clear();
    }


    public static int getTrackedChunkCount() {
        return chunkAccessTimes.size();
    }
}
