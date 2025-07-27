package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MemoryOptimizer {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-MPEM-Memory");
    private static long lastCleanTime = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.getServer().overworld() == null) return;

        var config = ArclightConfig.spec().getOptimization().getMemoryOptimization();

        long currentTime = System.currentTimeMillis();
        long interval = config.getCacheCleanupInterval() * 1000L;

        if (currentTime - lastCleanTime > interval) {
            performMemoryCleanup(event.getServer(), config);
            lastCleanTime = currentTime;
        }
    }

    private static void performMemoryCleanup(net.minecraft.server.MinecraftServer server, io.izzel.arclight.i18n.conf.MemoryOptimizationSpec config) {
        try {
            if (config.isCacheCleanupEnabled()) {
                cleanupCaches();
            }

            LOGGER.debug("Luminara cache cleanup completed");

        } catch (Exception e) {
            LOGGER.error("Error during cache cleanup", e);
        }
    }


    private static void cleanupCaches() {
        try {
            System.runFinalization();
        } catch (Exception e) {
            LOGGER.warn("Failed to cleanup caches", e);
        }
    }

    public static double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        return (double) (totalMemory - freeMemory) / totalMemory;
    }

    public static void forceCleanup() {
        var config = ArclightConfig.spec().getOptimization().getMemoryOptimization();
        if (config.isCacheCleanupEnabled()) {
            cleanupCaches();
        }
        lastCleanTime = 0;
    }
}
