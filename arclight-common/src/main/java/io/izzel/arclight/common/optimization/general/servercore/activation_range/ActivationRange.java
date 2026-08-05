package io.izzel.arclight.common.optimization.general.servercore.activation_range;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_FullActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/**
 * 实体激活范围（移植自 ServerCore，源出 Paper/Spigot Entity-Activation-Range，作者 Aikar，GPL-3.0）。
 * 接口注入改为 EntityBridge_FullActivationRange 桥强转，配置改由 ServerCoreConfig 提供。
 */
public final class ActivationRange {

    private static final double MINIMUM_MOVEMENT = 0.001;
    private static final Predicate<Goal> BEE_GOAL_IMMUNITIES =
            goal -> goal instanceof Bee.BeeGoToKnownFlowerGoal || goal instanceof Bee.BeeGoToHiveGoal;
    private static final Activity[] VILLAGER_PANIC_IMMUNITIES = {
            Activity.HIDE,
            Activity.PRE_RAID,
            Activity.RAID,
            Activity.PANIC
    };

    /**
     * 实体初始化时分配其所属激活类型。
     */
    public static ActivationType initializeEntityActivationType(Entity entity) {
        ActivationRangeConfig config = ServerCoreConfig.activationRange();
        // 关闭时跳过匹配，避免每次实体构造遍历类型表
        if (!config.enabled()) {
            return config.defaultActivationType();
        }
        for (CustomActivationType type : config.activationTypes()) {
            for (EntityTypeTest<? super Entity, ?> matcher : type.matchers()) {
                if (matcher.tryCast(entity) != null) {
                    return type;
                }
            }
        }
        return config.defaultActivationType();
    }

    /**
     * 排除名单内的实体永远正常 tick，不受激活范围影响。
     */
    public static boolean isExcluded(Entity entity) {
        final ActivationType type = bridge(entity).bridge$getActivationType();
        final int tickInterval = type.tickInterval();

        return tickInterval == 0 || tickInterval == 1 || type.activationRange() <= 0
                || entity instanceof Player
                || entity instanceof ThrowableItemProjectile
                || entity instanceof EnderDragon
                || entity instanceof EnderDragonPart
                || entity instanceof WitherBoss
                || entity instanceof Fireball
                || entity instanceof LightningBolt
                || entity instanceof PrimedTnt
                || entity instanceof EndCrystal
                || entity instanceof FireworkRocketEntity
                || entity instanceof EyeOfEnder
                || entity instanceof ThrownTrident
                || ServerCoreConfig.activationRange().excludedEntityTypes().contains(entity.getType());
    }

    /**
     * 激活该世界中离玩家足够近的实体。
     */
    public static void activateEntities(ServerLevel level, int currentTick) {
        ActivationRangeConfig config = ServerCoreConfig.activationRange();
        if (!config.enabled()) {
            return;
        }

        int maxRange = Integer.MIN_VALUE;
        for (CustomActivationType type : config.activationTypes()) {
            maxRange = Math.max(type.activationRange(), maxRange);
        }

        maxRange = Math.min((level.getServer().getPlayerList().getViewDistance() << 4) - 8, maxRange);
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                AABB maxBB = player.getBoundingBox().inflate(maxRange, 256, maxRange);
                for (Entity entity : level.getEntities(player, maxBB)) {
                    activateEntity(player, entity, currentTick, config);
                }
            }
        }
    }

    private static void activateEntity(ServerPlayer player, Entity entity, int currentTick, ActivationRangeConfig config) {
        EntityBridge_FullActivationRange bridge = bridge(entity);
        if (currentTick > bridge.bridge$getActivatedTick()) {
            if (bridge.bridge$isExcludedFromActivation() || isWithinRange(player, entity, config)) {
                bridge.bridge$setActivatedTick(currentTick + 19);
            }
        }
    }

    private static boolean isWithinRange(ServerPlayer player, Entity entity, ActivationRangeConfig config) {
        final ActivationType type = bridge(entity).bridge$getActivationType();
        final int range = type.activationRange();
        final int chessboardDistance = Math.max(
                Math.abs(player.getBlockX() - entity.getBlockX()),
                Math.abs(player.getBlockZ() - entity.getBlockZ())
        );

        if (chessboardDistance > range) {
            return false;
        }

        if (config.useVerticalRange()) {
            final int deltaY = entity.getBlockY() - player.getBlockY();
            return deltaY <= range && deltaY >= -range
                    || (deltaY > 0 && type.extraHeightUp())
                    || (deltaY < 0 && type.extraHeightDown());
        }

        return true;
    }

    /**
     * 检查非激活实体的免疫条件，返回应保持免疫的 tick 数（-1 表示无免疫）。
     */
    public static int checkEntityImmunities(Entity entity, int currentTick, ActivationRangeConfig config) {
        final EntityBridge_FullActivationRange bridge = bridge(entity);
        final int inactiveWakeUpImmunity = checkInactiveWakeup(entity, currentTick);
        if (inactiveWakeUpImmunity > -1) {
            return inactiveWakeUpImmunity;
        }

        if (entity.getRemainingFireTicks() > 0) {
            return 2;
        }

        if (bridge.bridge$getActivatedImmunityTick() >= currentTick) {
            return 1;
        }

        if (!entity.isAlive()) {
            return 40;
        }

        // 快速判据
        if (entity.isInWater() && entity.isPushedByFluid()
                && !(entity instanceof AgeableMob || entity instanceof Villager || entity instanceof Boat)) {
            return 100;
        }

        // 运动中的掉落物与经验球免疫
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            final Vec3 movement = entity.getDeltaMovement();
            if (Math.abs(movement.x) > MINIMUM_MOVEMENT || Math.abs(movement.z) > MINIMUM_MOVEMENT || movement.y > MINIMUM_MOVEMENT) {
                return 20;
            }
        }

        if (!(entity instanceof AbstractArrow projectile)) {
            if (!entity.onGround() && !entity.isInWater() && !(entity instanceof FlyingMob || entity instanceof Bat)) {
                return 10;
            }
        } else if (!projectile.inGround) {
            return 1;
        }

        // 特殊情形
        if (entity instanceof LivingEntity living) {
            if (!living.getActiveEffects().isEmpty() || living.onClimbable()) {
                return 1;
            }

            if (living instanceof Mob mob) {
                if (mob.getTarget() != null || mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                    return 20;
                }

                // 1.20.1 适配：Bee.isAngry/beePollinateGoal.isPollinating 为包私有，跳过这两个免疫分支
                if (mob instanceof Bee bee
                        && hasTasks(bee.goalSelector, BEE_GOAL_IMMUNITIES)) {
                    return 20;
                }

                if (mob instanceof Villager villager) {
                    Brain<Villager> brain = villager.getBrain();

                    if (config.villagerTickPanic()) {
                        for (Activity activity : VILLAGER_PANIC_IMMUNITIES) {
                            if (brain.isActive(activity)) {
                                return 20 * 5;
                            }
                        }
                    }

                    final int immunityAfter = config.villagerWorkImmunityAfter();
                    if (immunityAfter > 0 && (currentTick - bridge.bridge$getActivatedTick()) >= immunityAfter) {
                        if (brain.isActive(Activity.WORK)) {
                            return config.villagerWorkImmunityFor();
                        }
                    }
                }

                if (mob instanceof Llama llama && llama.inCaravan()) {
                    return 1;
                }

                if (mob instanceof Animal animal) {
                    if (animal.isBaby() || animal.isInLove()) {
                        return 5;
                    }

                    if (mob instanceof Sheep sheep && sheep.isSheared()) {
                        return 1;
                    }
                }

                if (mob instanceof Creeper creeper && creeper.isIgnited()) {
                    return 20;
                }

                if (hasTasks(mob.targetSelector, null)) {
                    return 0;
                }
            }
        }
        return -1;
    }

    /**
     * 判定实体是否处于激活状态；非激活时每秒检查一次免疫条件。
     */
    public static boolean checkIfActive(Entity entity, int currentTick) {
        ActivationRangeConfig config = ServerCoreConfig.activationRange();
        EntityBridge_FullActivationRange bridge = bridge(entity);
        if (shouldTick(entity, bridge, config)) {
            bridge.bridge$setActivatedTick(currentTick);
            return true;
        }

        boolean active = bridge.bridge$getActivatedTick() >= currentTick;
        if (!active) {
            final int inactiveTicks = currentTick - bridge.bridge$getActivatedTick() - 1;
            if (inactiveTicks % 20 == 0) {
                final int immunity = checkEntityImmunities(entity, currentTick, config);
                if (immunity >= 0) {
                    bridge.bridge$setActivatedTick(currentTick + immunity);
                    return true;
                }
            }

            final int tickInterval = bridge.bridge$getActivationType().tickInterval();
            if (tickInterval > 0 && inactiveTicks % tickInterval == 0) {
                return true;
            }
            // Spigot: 对激活但不免疫的实体跳过 1/4 tick
        } else if (config.skipNonImmune() && bridge.bridge$getFullTickCount() % 4 == 0
                && checkEntityImmunities(entity, currentTick, config) < 0) {
            return false;
        }

        return active;
    }

    private static boolean shouldTick(Entity entity, EntityBridge_FullActivationRange bridge, ActivationRangeConfig config) {
        return !config.enabled() || bridge.bridge$isExcludedFromActivation() || entity.isOnPortalCooldown()
                || (entity.tickCount < 200 && (bridge.bridge$getActivationType() == config.defaultActivationType() || config.tickNewEntities()))
                || (entity instanceof Mob mob && mob.isLeashed() && mob.getLeashHolder() instanceof Player)
                || (entity instanceof LivingEntity living && living.hurtTime > 0);
    }

    private static int checkInactiveWakeup(Entity entity, int currentTick) {
        EntityBridge_FullActivationRange bridge = bridge(entity);
        int wakeupInterval = bridge.bridge$getActivationType().wakeupInterval();
        if (wakeupInterval > 0 && currentTick - bridge.bridge$getActivatedTick() >= wakeupInterval * 20L) {
            return 100;
        }
        return -1;
    }

    public static boolean hasTasks(GoalSelector selector, Predicate<Goal> predicate) {
        for (WrappedGoal wrapped : selector.getAvailableGoals()) {
            if (wrapped.isRunning() && (predicate == null || predicate.test(wrapped.getGoal()))) {
                return true;
            }
        }
        return false;
    }

    private static EntityBridge_FullActivationRange bridge(Entity entity) {
        return (EntityBridge_FullActivationRange) entity;
    }

    private ActivationRange() {
    }
}
