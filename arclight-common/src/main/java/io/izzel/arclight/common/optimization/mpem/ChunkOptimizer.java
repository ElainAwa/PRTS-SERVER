package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkOptimizer {
    private static final Logger LOGGER = ArclightI18nLogger.getLogger("ChunkOptimizer");
    private static final Map<ChunkPos, Long> chunkAccessTimes = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getChunkOptimization();
        if (!config.isAggressiveChunkUnloading()) return;

        long currentTime = System.currentTimeMillis();
        long unloadThreshold = config.getChunkUnloadDelay() * 1000L;

        // Update active chunks
        for (ServerLevel level : event.getServer().getAllLevels()) {
            level.getPlayers(player -> {
                ChunkPos pos = new ChunkPos(player.chunkPosition().x, player.chunkPosition().z);
                chunkAccessTimes.put(pos, currentTime);
                return false;
            });
        }

        // Unload inactive chunks
        int unloadedCount = 0;
        chunkAccessTimes.entrySet().removeIf(entry -> {
            if (currentTime - entry.getValue() > unloadThreshold) {
                for (ServerLevel level : event.getServer().getAllLevels()) {
                    if (level.getChunkSource().hasChunk(entry.getKey().x, entry.getKey().z)) {
                        // Trigger chunk unloading
                        level.getChunkSource().tick(() -> true, true);
                        LOGGER.debug("optimization.chunk.unloading", entry.getKey().x, entry.getKey().z, level.dimension().location());
                    }
                }
                return true;
            }
            return false;
        });

        if (unloadedCount > 0) {
            LOGGER.info("optimization.chunk.unloaded", unloadedCount);
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


}
