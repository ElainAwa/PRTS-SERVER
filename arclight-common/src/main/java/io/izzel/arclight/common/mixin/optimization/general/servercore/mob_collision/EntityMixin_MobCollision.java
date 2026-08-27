/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_collision;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 生物间碰撞开关(默认关):非玩家实体不查询实体碰撞、不重叠推开(消除 O(n²) 热点);
 * 玩家碰撞保留(players-affected=true 时全量);方块碰撞/攻击击退不受影响。
 */
@Mixin(Entity.class)
public abstract class EntityMixin_MobCollision {

    /** 当前实体是否应跳过实体碰撞查询(玩家保留;players-affected=true 时全量)。 */
    private static boolean mobCollisionDisabled(Entity entity) {
        if (!PRTSFeaturesConfig.mobCollisionEnabled) {
            return false;
        }
        if (!PRTSFeaturesConfig.mobCollisionPlayersAffected && entity instanceof Player) {
            return false;
        }
        return true;
    }

    @Redirect(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> arclight$noMobCollision(Level level, Entity entity, AABB aabb) {
        if (mobCollisionDisabled(entity)) {
            return List.of();
        }
        return level.getEntityCollisions(entity, aabb);
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void arclight$noMobPush(Entity other, CallbackInfo ci) {
        if (mobCollisionDisabled((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
