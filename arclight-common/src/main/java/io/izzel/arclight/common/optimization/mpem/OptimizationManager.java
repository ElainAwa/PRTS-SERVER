package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.common.mod.command.LuminaraCommand;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import io.izzel.arclight.common.optimization.mpem.async.AsyncAIManager;
import io.izzel.arclight.common.optimization.mpem.async.AsyncCollisionSystem;
import io.izzel.arclight.common.optimization.mpem.async.AsyncRedstoneManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.Logger;

public class OptimizationManager {
    private static final Logger LOGGER = ArclightI18nLogger.getLogger("Luminara-MPEM-Manager");
    private static boolean initialized = false;

    public static void initialize(FMLCommonSetupEvent event) {
        if (initialized) return;
        initialized = true;

        event.enqueueWork(() -> initializeOptimizationSystems());
    }

    private static void initializeOptimizationSystems() {
        MpemThreadManager.initialize();
        AsyncEventSystem.initialize();
        AsyncAIManager.initialize();
        AsyncCollisionSystem.initialize();
        AsyncRedstoneManager.initialize();

        MinecraftForge.EVENT_BUS.register(EntityOptimizer.class);
        MinecraftForge.EVENT_BUS.register(ChunkOptimizer.class);
        MinecraftForge.EVENT_BUS.register(MemoryOptimizer.class);
        MinecraftForge.EVENT_BUS.register(EntitySyncOptimizer.class);
        MinecraftForge.EVENT_BUS.register(EntityCleaner.class);
        MinecraftForge.EVENT_BUS.register(AsyncAIManager.class);
        MinecraftForge.EVENT_BUS.register(AsyncCollisionSystem.class);
        MinecraftForge.EVENT_BUS.register(AsyncRedstoneManager.class);
        MinecraftForge.EVENT_BUS.register(LuminaraCommand.class);

        Runtime.getRuntime().addShutdownHook(new Thread(OptimizationManager::shutdown));
    }

    public static void shutdown() {
        if (!initialized) return;

        try {
            AsyncEventSystem.shutdown();
            AsyncAIManager.shutdown();
            AsyncCollisionSystem.shutdown();
            AsyncRedstoneManager.shutdown();
            MpemThreadManager.shutdown();
        } catch (Exception e) {
            LOGGER.error("optimization.manager.shutdown-error", e);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
