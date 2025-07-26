package io.izzel.arclight.common.optimization.mpem.async;

import io.izzel.arclight.common.optimization.mpem.MpemThreadManager;
import io.izzel.arclight.i18n.ArclightConfig;
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

        server.getAllLevels().forEach(level -> {
            List<Entity> entities = new ArrayList<>();
            level.getAllEntities().forEach(entities::add);


            entities.stream()
                    .filter(entity -> !(entity instanceof Player))
                    .filter(AsyncCollisionSystem::shouldProcessAsync)
                    .forEach(entity -> {
                        MpemThreadManager.runAsync(() -> processEntityCollisions(entity, entities))
                                .exceptionally(throwable -> {
                                    LOGGER.warn("Error processing async collision for entity {}", entity.getType(), throwable);
                                    return null;
                                });
                    });
        });
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
}
