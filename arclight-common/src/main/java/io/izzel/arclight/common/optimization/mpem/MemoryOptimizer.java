package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.Logger;

public class MemoryOptimizer {
    private static final Logger LOGGER = ArclightI18nLogger.getLogger("Luminara-MPEM-Memory");
    private static long lastCleanTime = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.getServer().overworld() == null) return;

        var config = ArclightConfig.spec().getOptimization().getMemoryOptimization();

        long currentTime = System.currentTimeMillis();
        long interval = config.getCacheCleanupInterval() * 1000L;

        if (currentTime - lastCleanTime > interval) {
            // Check memory usage before cleanup
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();

            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

            if (memoryUsagePercent > 80) {
                LOGGER.warn("optimization.memory.high-usage", String.format("%.1f", memoryUsagePercent));
            }

            LOGGER.info("optimization.memory.cleanup-start");
            performMemoryCleanup(event.getServer(), config);
            lastCleanTime = currentTime;
        }
    }

    private static void performMemoryCleanup(net.minecraft.server.MinecraftServer server, io.izzel.arclight.i18n.conf.MemoryOptimizationSpec config) {
        try {
            if (config.isCacheCleanupEnabled()) {
                cleanupCaches();
            }

            LOGGER.debug("optimization.memory.cache-cleanup-completed");

            // Calculate memory freed
            Runtime runtime = Runtime.getRuntime();
            long newUsedMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryFreed = (runtime.totalMemory() - runtime.freeMemory()) - newUsedMemory;
            if (memoryFreed > 0) {
                LOGGER.info("optimization.memory.cleanup-complete", memoryFreed / 1024 / 1024);
            }

        } catch (Exception e) {
            LOGGER.error("optimization.memory.cache-cleanup-error", e);
        }
    }


    private static void cleanupCaches() {
        try {
            System.runFinalization();
            System.gc();
            LOGGER.debug("optimization.memory.gc-triggered");
        } catch (Exception e) {
            LOGGER.warn("optimization.memory.cache-cleanup-failed", e);
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
