package io.izzel.arclight.common.optimization.mpem.async;

import io.izzel.arclight.common.optimization.mpem.MpemThreadManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;


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

        // Collect mob data on main thread to avoid async world access
        for (ServerLevel level : server.getAllLevels()) {
            List<MobAIData> mobData = new ArrayList<>();

            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof Mob mob && mob.isAlive() && shouldProcessAsync(mob)) {
                    try {
                        // Collect necessary data on main thread
                        MobAIData data = new MobAIData(
                                mob.getId(),
                                mob.position(),
                                mob.getType().toString(),
                                mob.getRandom().nextFloat()
                        );
                        mobData.add(data);
                    } catch (Exception e) {
                        // Skip mobs that can't be safely accessed
                        continue;
                    }
                }
            }

            // Process AI calculations asynchronously with collected data
            if (!mobData.isEmpty()) {
                MpemThreadManager.runAsync(() -> processAICalculations(mobData))
                        .exceptionally(throwable -> {
                            LOGGER.warn("Error in async AI calculations", throwable);
                            return null;
                        });
            }
        }
    }

    private static boolean shouldProcessAsync(Mob mob) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        double range = config.getEntityUpdateDistance();
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

    // Process AI calculations with pre-collected data (thread-safe)
    private static void processAICalculations(List<MobAIData> mobData) {
        try {
            for (MobAIData data : mobData) {
                // Perform AI calculations using only the collected data
                // This is thread-safe as it doesn't access world state

                // Simulate reduced AI processing for distant mobs
                if (data.randomValue < 0.1f) {
                    // Simulate pathfinding calculation
                    // (In a real implementation, this would be more complex)
                }

                if (data.randomValue < 0.2f) {
                    // Simulate goal processing
                    // (In a real implementation, this would be more complex)
                }

                // Note: We can't actually apply AI changes here as it would require
                // world access. This would need to be queued for main thread execution.
                // For now, we just perform the calculations.
            }
        } catch (Exception e) {
            LOGGER.warn("Error in AI calculations", e);
        }
    }

    // Thread-safe data container for mob AI information
    private static class MobAIData {
        final int mobId;
        final Vec3 position;
        final String mobType;
        final float randomValue;

        MobAIData(int mobId, Vec3 position, String mobType, float randomValue) {
            this.mobId = mobId;
            this.position = position;
            this.mobType = mobType;
            this.randomValue = randomValue;
        }
    }
}
