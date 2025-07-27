package io.izzel.arclight.common.optimization.mpem;

import io.izzel.arclight.i18n.ArclightConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class EntityOptimizer {
    private static final Map<Entity, Long> inactiveEntities = new WeakHashMap<>();
    private static final Map<Entity, Boolean> activeEntities = new ConcurrentHashMap<>();
    // Entity type sets for filtering
    private static final Set<String> ALWAYS_TICKING_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<String> BOSS_ENTITIES = ConcurrentHashMap.newKeySet();

    static {
        // Initialize always ticking entities
        ALWAYS_TICKING_ENTITIES.add("minecraft:player");
        ALWAYS_TICKING_ENTITIES.add("minecraft:item");
        ALWAYS_TICKING_ENTITIES.add("minecraft:experience_orb");
        ALWAYS_TICKING_ENTITIES.add("minecraft:painting");
        ALWAYS_TICKING_ENTITIES.add("minecraft:item_frame");

        // Initialize boss entities
        BOSS_ENTITIES.add("minecraft:ender_dragon");
        BOSS_ENTITIES.add("minecraft:wither");
        BOSS_ENTITIES.add("minecraft:elder_guardian");
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        if (!config.isDisableEntityCollisions()) return;

        Entity entity = event.getEntity();


        if (!(entity instanceof Player)) {
            // Apply entity modifications on main thread to avoid async world access
            entity.setNoGravity(true);
            entity.noPhysics = false;
            inactiveEntities.put(entity, System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        inactiveEntities.remove(event.getEntity());
        activeEntities.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();


        if (event.getServer().getTickCount() % config.getEntityCheckInterval() == 0) {
            activeEntities.keySet().removeIf(e -> !e.isAlive());
            processInactiveEntities(config);
        }
    }

    public static void processInactiveEntities(io.izzel.arclight.i18n.conf.EntityOptimizationSpec config) {
        if (!config.isDisableEntityCollisions()) return;


        // Process entity freezing on main thread to avoid async world access
        long now = System.currentTimeMillis();
        long timeout = config.getEntityFreezeTimeout();
        Iterator<Map.Entry<Entity, Long>> iterator = inactiveEntities.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Entity, Long> entry = iterator.next();
            Entity entity = entry.getKey();

            if (!entity.isAlive()) {
                iterator.remove();
                continue;
            }

            // Freeze entity after timeout
            if (now - entry.getValue() > timeout) {
                entity.setDeltaMovement(Vec3.ZERO);
                entity.setPos(entity.getX(), entity.getY(), entity.getZ());
            }
        }
    }

    public static boolean isEntityActive(Entity entity) {
        return activeEntities.getOrDefault(entity, true);
    }

    public static boolean shouldOptimizeEntityTick(Entity entity) {
        if (entity instanceof Player) return false;
        if (entity instanceof net.minecraft.world.entity.boss.EnderDragonPart) return false;
        if (!(entity instanceof LivingEntity livingEntity)) return false;
        if (!livingEntity.isAlive()) return false;
        if (isAlwaysTicking(entity)) return false;
        if (isBossMob(livingEntity)) return false;

        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();
        double distance = config.getEntityUpdateDistance();
        Player nearestPlayer = entity.level().getNearestPlayer(entity, distance);

        if (nearestPlayer == null) {
            return true;
        }

        if (nearestPlayer.distanceToSqr(entity) > distance * distance * 4) {
            return true;
        }

        return false;
    }

    public static boolean shouldReduceEntityUpdates(Entity entity) {
        var config = ArclightConfig.spec().getOptimization().getEntityOptimization();

        if (!config.isReduceEntityUpdates()) return false;
        if (entity instanceof Player) return false;

        double distance = config.getEntityUpdateDistance();
        return entity.level().getNearestPlayer(entity, distance) == null;
    }

    // Methods from EntityTickHelper - merged to avoid duplication
    private static boolean isAlwaysTicking(Entity entity) {
        String entityType = entity.getType().toString();
        return ALWAYS_TICKING_ENTITIES.contains(entityType);
    }

    private static boolean isBossMob(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon ||
                entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
            return true;
        }

        String entityType = entity.getType().toString();
        return BOSS_ENTITIES.contains(entityType);
    }
}
