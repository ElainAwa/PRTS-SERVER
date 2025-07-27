package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class EntitySyncOptimizer {

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        if (!config.isReduceEntityUpdates()) return;

        Entity entity = event.getEntity();

        // Optimize entity synchronization for distant players
        double updateDistance = config.getEntityUpdateDistance();

        if (entity instanceof ServerPlayer player) {
            boolean hasNearbyPlayers = entity.level().players().stream()
                    .filter(p -> p != player)
                    .anyMatch(p -> p.distanceToSqr(entity) <= updateDistance * updateDistance);

            if (!hasNearbyPlayers) {
                return;
            }
        }

        if (!(entity instanceof ServerPlayer)) {
            boolean hasNearbyPlayers = entity.level().players().stream()
                    .anyMatch(p -> p.distanceToSqr(entity) <= updateDistance * updateDistance);

            if (!hasNearbyPlayers) {
                return;
            }
        }
    }

    public static boolean shouldReduceEntitySync(Entity entity) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        if (!config.isReduceEntityUpdates()) return false;

        if (entity instanceof ServerPlayer) return false;

        double updateDistance = config.getEntityUpdateDistance();
        return entity.level().players().stream()
                .noneMatch(p -> p.distanceToSqr(entity) <= updateDistance * updateDistance);
    }

    public static int getOptimizedUpdateInterval(Entity entity) {
        if (shouldReduceEntitySync(entity)) {
            // Reduce update frequency for distant entities
            return 4; // Update every 4 ticks instead of every tick
        }
        return 1; // Normal update frequency
    }
}
