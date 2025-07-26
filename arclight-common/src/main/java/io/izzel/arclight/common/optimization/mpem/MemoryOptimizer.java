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
        long interval = config.getMemoryCleanInterval() * 1000L;

        if (currentTime - lastCleanTime > interval) {
            performMemoryCleanup(event.getServer(), config);
            lastCleanTime = currentTime;
        }
    }

    private static void performMemoryCleanup(net.minecraft.server.MinecraftServer server, io.izzel.arclight.i18n.conf.MemoryOptimizationSpec config) {
        try {
            // Check memory usage
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            double memoryUsage = (double) (totalMemory - freeMemory) / totalMemory;

            if (memoryUsage < config.getMemoryThreshold() && !config.isAggressiveCleanup()) {
                return;
            }


            // Clean various caches
            if (config.isCacheCleanupEnabled()) {
                cleanupCaches();
            }

            // Trigger GC if enabled
            if (config.isEnableGC()) {
                System.gc();
            }

            LOGGER.debug("Luminara-MPEM memory cleanup completed. Usage: {:.2f}%", memoryUsage * 100);

        } catch (Exception e) {
            LOGGER.error("Error during memory cleanup", e);
        }
    }


    private static void cleanupCaches() {
        // Clear various internal caches
        try {
            // This would clear various Minecraft internal caches
            // Implementation depends on specific cache structures
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
        // Force immediate cleanup regardless of interval
        lastCleanTime = 0;
    }
}
