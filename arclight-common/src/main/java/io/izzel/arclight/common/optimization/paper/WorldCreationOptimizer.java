package io.izzel.arclight.common.optimization.paper;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WorldCreationOptimizer {
    private static ExecutorService worldCreationExecutor;
    
    static {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();
        if (config.isParallelWorldInitialization()) {
            worldCreationExecutor = Executors.newFixedThreadPool(
                config.getMaxConcurrentWorldLoads(),
                r -> {
                    Thread t = new Thread(r, "Luminara-Paper-WorldCreation");
                    t.setDaemon(true);
                    return t;
                }
            );
        }
    }


    public static void optimizeWorldInit(ServerLevel world, ServerLevelData worldData) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();
        
        if (!config.isFastWorldCreation()) {
            return;
        }



        if (config.isAsyncWorldDataLoading()) {
            loadWorldDataAsync(world, worldData);
        }

        PaperChunkOptimizer.optimizeWorldCreationChunks(world);

        preventWorldCreationDuringTick();
    }

    private static void preventWorldCreationDuringTick() {
    }


    private static void loadWorldDataAsync(ServerLevel world, ServerLevelData worldData) {
        if (worldCreationExecutor == null) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                world.getDataStorage();
            } catch (Exception ignored) {
            }
        }, worldCreationExecutor);
    }

    public static void optimizeSpawnAreaPreparation(ServerLevel world) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();

        if (config.isDeferSpawnAreaPreparation()) {
            return;
        }

        int radius = config.getSpawnAreaRadius();

        optimizeSpawnChunkLoading(world, radius);
    }

    private static void optimizeSpawnChunkLoading(ServerLevel world, int radius) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();

        if (!config.isSkipSpawnChunkLoading()) {
            var spawn = world.getSharedSpawnPos();

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    world.getChunkSource().addRegionTicket(
                        net.minecraft.server.level.TicketType.START,
                        new net.minecraft.world.level.ChunkPos(spawn.offset(x * 16, 0, z * 16)),
                        2, net.minecraft.util.Unit.INSTANCE
                    );
                }
            }
        }
    }


    public static boolean shouldSkipWorldCreation(String worldName) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();
        
        if (!config.isFastWorldCreation()) {
            return false;
        }
        return false;
    }


    public static void optimizeWorldBorder(ServerLevel world) {
        var config = ArclightConfig.spec().getOptimization().getWorldCreation();
        
        if (!config.isOptimizeWorldBorderSetup()) {
            return;
        }


    }


    public static void shutdown() {
        if (worldCreationExecutor != null) {
            worldCreationExecutor.shutdown();
            try {
                if (!worldCreationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    worldCreationExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                worldCreationExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }


    public static boolean isFastWorldCreationEnabled() {
        return ArclightConfig.spec().getOptimization().getWorldCreation().isFastWorldCreation();
    }


}
