package io.izzel.arclight.common.mixin.optimization.general.async;

import io.izzel.arclight.common.mixin.optimization.general.MpemThreadManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class AsyncAIManager {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-MPEM-AsyncAI");
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncAIEnabled()) return;


    }

    public static void shutdown() {
        if (!initialized) return;

    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncAIEnabled() || !initialized) return;

        if (event.getServer().getTickCount() % 4 == 0) {
            processAsyncAI(event.getServer());
        }
    }

    private static void processAsyncAI(net.minecraft.server.MinecraftServer server) {
        if (!initialized || !MpemThreadManager.isHealthy()) return;

        server.getAllLevels().forEach(level -> {
            level.getAllEntities().forEach(entity -> {
                if (entity instanceof Mob mob && mob.isAlive()) {

                    if (shouldProcessAsync(mob)) {
                        MpemThreadManager.runAsync(() -> processMobAI(mob))
                                .exceptionally(throwable -> {
                                    LOGGER.warn("Error processing async AI for mob {}", mob.getType(), throwable);
                                    return null;
                                });
                    }
                }
            });
        });
    }

    private static boolean shouldProcessAsync(Mob mob) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        double range = config.getEntityActivationRange();


        return mob.level().getNearestPlayer(mob, range) == null;
    }

    private static void processMobAI(Mob mob) {
        try {

            if (mob.getNavigation() != null && mob.getRandom().nextFloat() < 0.1f) {
                mob.getNavigation().tick();
            }

            if (mob.goalSelector != null && mob.getRandom().nextFloat() < 0.2f) {
                for (var goal : mob.goalSelector.getAvailableGoals()) {
                    if (goal.getGoal().canUse()) {
                        goal.getGoal().tick();
                        break;
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.warn("Error in async AI processing for mob {}", mob.getType(), e);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isHealthy() {
        return initialized && MpemThreadManager.isHealthy();
    }
}
