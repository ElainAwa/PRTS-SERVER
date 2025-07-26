package io.izzel.arclight.common.optimization.mpem.async;

import io.izzel.arclight.common.optimization.mpem.MpemThreadManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AsyncCollisionSystem {
    private static final Logger LOGGER = LogManager.getLogger("Luminara-MPEM-AsyncCollision");
    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncCollisionEnabled()) return;


    }

    public static void shutdown() {
        if (!initialized) return;

    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getAsyncSystem();
        if (!config.isAsyncCollisionEnabled() || !initialized) return;

        if (event.getServer().getTickCount() % 2 == 0) {
            processAsyncCollisions(event.getServer());
        }
    }

    private static void processAsyncCollisions(net.minecraft.server.MinecraftServer server) {
        if (!initialized || !MpemThreadManager.isHealthy()) return;

        // Collect entity data on main thread to avoid async chunk access
        for (ServerLevel level : server.getAllLevels()) {
            List<EntityCollisionData> entityData = new ArrayList<>();

            // Safely collect entity data on main thread
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof Player) continue;
                if (!shouldProcessAsync(entity)) continue;

                try {
                    // Collect all necessary data on main thread
                    EntityCollisionData data = new EntityCollisionData(
                            entity.getId(),
                            entity.position(),
                            entity.getBoundingBox(),
                            entity.isAlive()
                    );
                    entityData.add(data);
                } catch (Exception e) {
                    // Skip entities that can't be safely accessed
                    continue;
                }
            }

            // Process collision calculations asynchronously with collected data
            if (!entityData.isEmpty()) {
                MpemThreadManager.runAsync(() -> processCollisionCalculations(entityData))
                        .exceptionally(throwable -> {
                            LOGGER.warn("Error in async collision calculations", throwable);
                            return null;
                        });
            }
        }
    }

    private static boolean shouldProcessAsync(Entity entity) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        double range = config.getEntityActivationRange();


        return entity.level().getNearestPlayer(entity, range) == null;
    }

    private static void processEntityCollisions(Entity entity, List<Entity> allEntities) {
        try {
            if (!entity.isAlive()) return;

            AABB entityBounds = entity.getBoundingBox();
            Vec3 entityPos = entity.position();


            for (Entity other : allEntities) {
                if (other == entity || !other.isAlive()) continue;


                if (entityPos.distanceToSqr(other.position()) > 64.0) continue;

                AABB otherBounds = other.getBoundingBox();


                if (entityBounds.intersects(otherBounds)) {
                    handleAsyncCollision(entity, other);
                }
            }

        } catch (Exception e) {
            LOGGER.warn("Error in async collision processing for entity {}", entity.getType(), e);
        }
    }

    private static void handleAsyncCollision(Entity entity1, Entity entity2) {
        try {
            Vec3 pos1 = entity1.position();
            Vec3 pos2 = entity2.position();
            Vec3 diff = pos1.subtract(pos2).normalize();

            double force = 0.1;
            Vec3 separation = diff.scale(force);
            if (!entity1.isPassenger() && !entity1.isVehicle()) {
                entity1.setDeltaMovement(entity1.getDeltaMovement().add(separation));
            }

            if (!entity2.isPassenger() && !entity2.isVehicle()) {
                entity2.setDeltaMovement(entity2.getDeltaMovement().subtract(separation));
            }

        } catch (Exception e) {
            LOGGER.warn("Error handling async collision between {} and {}",
                    entity1.getType(), entity2.getType(), e);
        }
    }

    public static CompletableFuture<Boolean> checkCollisionAsync(Entity entity, AABB bounds) {
        if (!initialized) {
            return CompletableFuture.completedFuture(false);
        }

        return MpemThreadManager.supplyAsync(() -> {
            try {
                List<Entity> nearbyEntities = entity.level().getEntities(entity, bounds);
                return !nearbyEntities.isEmpty();
            } catch (Exception e) {
                LOGGER.warn("Error in async collision check", e);
                return false;
            }
        });
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean isHealthy() {
        return initialized && MpemThreadManager.isHealthy();
    }

    // Process collision calculations with pre-collected data
    private static void processCollisionCalculations(List<EntityCollisionData> entityData) {
        try {
            for (int i = 0; i < entityData.size(); i++) {
                EntityCollisionData entity1 = entityData.get(i);
                if (!entity1.isAlive) continue;

                for (int j = i + 1; j < entityData.size(); j++) {
                    EntityCollisionData entity2 = entityData.get(j);
                    if (!entity2.isAlive) continue;

                    // Check distance first (cheap operation)
                    if (entity1.position.distanceToSqr(entity2.position) > 64.0) continue;

                    // Check bounding box intersection
                    if (entity1.boundingBox.intersects(entity2.boundingBox)) {
                        // Calculate separation force (pure math, no world access)
                        Vec3 diff = entity1.position.subtract(entity2.position).normalize();
                        double force = 0.1;
                        Vec3 separation = diff.scale(force);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error in collision calculations", e);
        }
    }

    // Thread-safe data container for entity collision information
    private static class EntityCollisionData {
        final int entityId;
        final Vec3 position;
        final AABB boundingBox;
        final boolean isAlive;

        EntityCollisionData(int entityId, Vec3 position, AABB boundingBox, boolean isAlive) {
            this.entityId = entityId;
            this.position = position;
            this.boundingBox = boundingBox;
            this.isAlive = isAlive;
        }
    }
}
