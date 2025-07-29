package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.common.util.NotificationManager;
import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import io.izzel.arclight.common.mod.util.log.ArclightI18nLogger;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

public class EntityCleaner {
    private static final Logger LOGGER = ArclightI18nLogger.getLogger("Luminara-MPEM-EntityCleaner");
    private static final int CLEAN_INTERVAL_TICKS = 12000;
    // Entity type patterns for filtering (similar to EClean's regex support)
    private static final Map<String, Pattern> entityPatterns = new ConcurrentHashMap<>();
    private static final Set<String> protectedEntityTypes = ConcurrentHashMap.newKeySet();
    // Chunk density tracking
    private static final Map<ChunkPos, Map<String, Integer>> chunkEntityCounts = new ConcurrentHashMap<>();
    private static final AtomicBoolean cleanupInProgress = new AtomicBoolean(false);
    private static long lastCleanTime = 0;
    // Cleanup scheduling
    private static ScheduledFuture<?> scheduledCleanup = null;

    static {
        // Initialize protected entity types (entities that should never be cleaned)
        protectedEntityTypes.add("minecraft:player");
        protectedEntityTypes.add("minecraft:armor_stand");
        protectedEntityTypes.add("minecraft:painting");
        protectedEntityTypes.add("minecraft:item_frame");
        protectedEntityTypes.add("minecraft:glow_item_frame");
        protectedEntityTypes.add("minecraft:end_crystal");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        if (!config.isEntityCleanupEnabled()) return;

        long currentTime = event.getServer().getTickCount();
        if (currentTime - lastCleanTime > CLEAN_INTERVAL_TICKS) {
            lastCleanTime = currentTime;
            scheduleCleanupWithNotification(event.getServer());
        }
    }

    private static void scheduleCleanupWithNotification(net.minecraft.server.MinecraftServer server) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();


        if (scheduledCleanup != null && !scheduledCleanup.isDone()) {
            scheduledCleanup.cancel(false);
            LOGGER.info("optimization.entity-cleanup.cancelled");
        }

        if (!config.isCleanupNotificationEnabled()) {

            performEntityCleanup(server);
            return;
        }


        if (cleanupInProgress.get()) {
            return;
        }

        int warningTime = config.getCleanupWarningTime();
        String startMessage = config.getCleanupStartMessage();


        scheduledCleanup = NotificationManager.scheduleCountdownNotification(
                server,
                startMessage,
                warningTime,
                () -> performEntityCleanup(server)
        );
    }

    private static void performEntityCleanup(net.minecraft.server.MinecraftServer server) {
        // Set cleanup in progress flag
        if (!cleanupInProgress.compareAndSet(false, true)) {
            return; // Cleanup already in progress
        }

        try {
            var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
            int cleanupThreshold = config.getEntityCleanupThreshold();

            // Estimate entities to clean
            int estimatedEntities = 0;
            for (ServerLevel level : server.getAllLevels()) {
                estimatedEntities += (int) StreamSupport.stream(level.getEntities().getAll().spliterator(), false).count();
            }
            LOGGER.info("optimization.entity-cleanup.starting", estimatedEntities);

            int totalCleaned = 0;
            CleanupStats stats = new CleanupStats();

            for (ServerLevel level : server.getAllLevels()) {
                try {
                    // Update chunk entity counts first
                    updateChunkEntityCounts(level);

                    // Perform different types of cleanup
                    totalCleaned += cleanupDeadEntities(level, cleanupThreshold, stats);
                    totalCleaned += cleanupOldItems(level, stats);
                    totalCleaned += cleanupDenseChunks(level, stats);
                    totalCleaned += cleanupExcessEntities(level, stats);
                } catch (Exception e) {
                    LOGGER.warn("optimization.entity-cleanup.level-error", level.dimension().location(), e.getMessage());
                }
            }

            // Send completion notification
            if (config.isCleanupNotificationEnabled() && totalCleaned > 0) {
                String completeMessage = NotificationManager.formatMessage(
                        config.getCleanupCompleteMessage(),
                        "total", totalCleaned,
                        "dead", stats.deadEntities,
                        "items", stats.oldItems,
                        "dense", stats.denseEntities,
                        "excess", stats.excessEntities
                );
                NotificationManager.broadcastMessage(server, completeMessage);
            }

            if (totalCleaned > 0) {
                LOGGER.info("optimization.entity-cleanup.mpem-completed",
                        totalCleaned, stats.deadEntities, stats.oldItems, stats.denseEntities, stats.excessEntities);
            }

        } finally {
            // Reset cleanup in progress flag
            cleanupInProgress.set(false);
        }
    }

    private static void updateChunkEntityCounts(ServerLevel level) {
        chunkEntityCounts.clear();

        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Player) continue;

            ChunkPos chunkPos = new ChunkPos(entity.blockPosition());
            String entityType = entity.getType().toString();

            chunkEntityCounts.computeIfAbsent(chunkPos, k -> new ConcurrentHashMap<>())
                    .merge(entityType, 1, Integer::sum);
        }
    }

    private static int cleanupDeadEntities(ServerLevel level, int threshold, CleanupStats stats) {
        int cleaned = 0;

        try {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof Player) continue;

                try {
                    // Clean truly dead entities
                    if (!entity.isAlive() && entity.tickCount > threshold) {
                        if (shouldCleanEntity(entity)) {
                            entity.discard();
                            cleaned++;
                            stats.deadEntities++;
                        }
                    }

                    // Clean entities that are stuck or invalid
                    if (entity.isAlive() && isStuckEntity(entity)) {
                        entity.discard();
                        cleaned++;
                        stats.deadEntities++;
                    }
                } catch (Exception e) {
                    // Skip problematic entity and continue
                    continue;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("optimization.entity-cleanup.dead-entities-error", e.getMessage());
        }

        return cleaned;
    }

    private static int cleanupOldItems(ServerLevel level, CleanupStats stats) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        int cleaned = 0;
        long maxAge = config.getItemMaxAge();

        try {
            for (Entity entity : level.getEntities().getAll()) {
                if (entity instanceof ItemEntity itemEntity) {
                    try {
                        // Don't clean valuable items unless configured to do so
                        if (!config.isCleanValuableItems() && isValuableItem(itemEntity)) continue;

                        if (itemEntity.getAge() > maxAge) {
                            itemEntity.discard();
                            cleaned++;
                            stats.oldItems++;
                        }
                    } catch (Exception e) {
                        // Skip problematic item and continue
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("optimization.entity-cleanup.old-items-error", e.getMessage());
        }

        return cleaned;
    }

    private static int cleanupDenseChunks(ServerLevel level, CleanupStats stats) {
        int cleaned = 0;

        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        int maxEntitiesPerChunk = config.getMaxEntitiesPerChunk();

        for (Map.Entry<ChunkPos, Map<String, Integer>> chunkEntry : chunkEntityCounts.entrySet()) {
            ChunkPos chunkPos = chunkEntry.getKey();
            Map<String, Integer> entityCounts = chunkEntry.getValue();

            // Calculate total entities in chunk
            int totalEntities = entityCounts.values().stream().mapToInt(Integer::intValue).sum();

            if (totalEntities > maxEntitiesPerChunk) {
                cleaned += cleanupChunkEntities(level, chunkPos, entityCounts, stats);
            }
        }

        return cleaned;
    }

    private static int cleanupExcessEntities(ServerLevel level, CleanupStats stats) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        int cleaned = 0;
        Map<String, List<Entity>> entityGroups = new HashMap<>();

        // Group entities by type
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Player) continue;
            if (!shouldCleanEntity(entity)) continue;

            String entityType = entity.getType().toString();
            entityGroups.computeIfAbsent(entityType, k -> new ArrayList<>()).add(entity);
        }

        int maxEntitiesPerType = config.getMaxEntitiesPerType();

        for (Map.Entry<String, List<Entity>> entry : entityGroups.entrySet()) {
            List<Entity> entities = entry.getValue();
            if (entities.size() > maxEntitiesPerType) {
                // Sort by distance from players (keep closer ones)
                entities.sort((e1, e2) -> {
                    double dist1 = getDistanceToNearestPlayer(e1);
                    double dist2 = getDistanceToNearestPlayer(e2);
                    return Double.compare(dist2, dist1); // Farthest first
                });

                // Remove excess entities (keep first maxEntitiesPerType)
                for (int i = maxEntitiesPerType; i < entities.size(); i++) {
                    entities.get(i).discard();
                    cleaned++;
                    stats.excessEntities++;
                }
            }
        }

        return cleaned;
    }

    private static int cleanupChunkEntities(ServerLevel level, ChunkPos chunkPos,
                                            Map<String, Integer> entityCounts, CleanupStats stats) {
        int cleaned = 0;

        // Check if chunk is loaded to avoid async chunk loading
        if (!level.hasChunk(chunkPos.x, chunkPos.z)) {
            return 0; // Skip unloaded chunks
        }

        // Find the most common entity type in this chunk
        String mostCommonType = entityCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        if (mostCommonType != null && entityCounts.get(mostCommonType) > 20) {
            // Clean excess entities of the most common type
            List<Entity> entitiesToClean = new ArrayList<>();

            for (Entity entity : level.getEntitiesOfClass(Entity.class,
                    new net.minecraft.world.phys.AABB(
                            chunkPos.getMinBlockX(), level.getMinBuildHeight(),
                            chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX() + 1,
                            level.getMaxBuildHeight(), chunkPos.getMaxBlockZ() + 1))) {

                if (entity.getType().toString().equals(mostCommonType) && shouldCleanEntity(entity)) {
                    entitiesToClean.add(entity);
                }
            }

            // Sort by distance from players and clean farthest ones
            entitiesToClean.sort((e1, e2) -> {
                double dist1 = getDistanceToNearestPlayer(e1);
                double dist2 = getDistanceToNearestPlayer(e2);
                return Double.compare(dist2, dist1);
            });

            var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
            int chunkEntityLimit = config.getChunkEntityLimit();
            for (int i = chunkEntityLimit; i < entitiesToClean.size(); i++) {
                entitiesToClean.get(i).discard();
                cleaned++;
                stats.denseEntities++;
            }
        }

        return cleaned;
    }

    private static boolean shouldCleanEntity(Entity entity) {
        if (entity instanceof Player) return false;

        String entityType = entity.getType().toString();

        // Check protected entity types
        if (protectedEntityTypes.contains(entityType)) return false;

        // Check if entity has custom name (usually indicates it's important)
        if (entity.hasCustomName()) return false;

        // Check if entity is leashed, being ridden, or is important
        if (entity instanceof LivingEntity living) {
            // Check if entity is being ridden or is a vehicle
            if (living.isVehicle() || living.isPassenger()) {
                return false;
            }

            // Don't clean leashed mobs
            if (living instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) {
                return false;
            }

            // Don't clean tamed animals
            if (living instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isTame()) {
                return false;
            }
        }

        // Check if item entity has valuable items
        if (entity instanceof ItemEntity itemEntity) {
            return !isValuableItem(itemEntity);
        }

        return true;
    }

    private static boolean isValuableItem(ItemEntity itemEntity) {
        var itemStack = itemEntity.getItem();

        // Don't clean enchanted items
        if (itemStack.isEnchanted()) return true;

        // Don't clean named items
        if (itemStack.hasCustomHoverName()) return true;

        // Don't clean rare items (could be expanded with more checks)
        String itemName = itemStack.getItem().toString();
        return itemName.contains("diamond") || itemName.contains("netherite") ||
                itemName.contains("emerald") || itemName.contains("gold");
    }

    private static boolean isStuckEntity(Entity entity) {
        // Check if entity hasn't moved for a long time and is in an invalid state
        if (entity.tickCount > 6000) { // 5 minutes
            BlockPos pos = entity.blockPosition();

            // Check if entity is in an unloaded chunk or invalid position
            if (!entity.level().hasChunkAt(pos)) return true;

            // Check if entity is stuck in blocks (basic check)
            if (entity.level().getBlockState(pos).isSolid() &&
                    entity.level().getBlockState(pos.above()).isSolid()) {
                return true;
            }
        }

        return false;
    }

    private static double getDistanceToNearestPlayer(Entity entity) {
        return entity.level().players().stream()
                .mapToDouble(player -> player.distanceToSqr(entity))
                .min()
                .orElse(Double.MAX_VALUE);
    }

    public static void forceCleanup(net.minecraft.server.MinecraftServer server) {
        LOGGER.info("optimization.entity-cleaner.forcing-cleanup");

        // Cancel any scheduled cleanup
        if (scheduledCleanup != null && !scheduledCleanup.isDone()) {
            scheduledCleanup.cancel(false);

            var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
            if (config.isCleanupNotificationEnabled()) {
                String cancelMessage = config.getCleanupCancelledMessage();
                NotificationManager.broadcastMessage(server, cancelMessage);
            }
        }

        // Ensure cleanup runs on main thread to avoid async chunk access
        if (server.isSameThread()) {
            performEntityCleanup(server);
        } else {
            server.execute(() -> performEntityCleanup(server));
        }
    }

    public static void cancelScheduledCleanup(net.minecraft.server.MinecraftServer server) {
        if (scheduledCleanup != null && !scheduledCleanup.isDone()) {
            scheduledCleanup.cancel(false);

            var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
            if (config.isCleanupNotificationEnabled()) {
                String cancelMessage = config.getCleanupCancelledMessage();
                NotificationManager.broadcastMessage(server, cancelMessage);
            }

            LOGGER.info("optimization.entity-cleaner.scheduled-cancelled");
        }
    }

    // Statistics class for tracking cleanup results
    private static class CleanupStats {
        int deadEntities = 0;
        int oldItems = 0;
        int denseEntities = 0;
        int excessEntities = 0;
    }
}
