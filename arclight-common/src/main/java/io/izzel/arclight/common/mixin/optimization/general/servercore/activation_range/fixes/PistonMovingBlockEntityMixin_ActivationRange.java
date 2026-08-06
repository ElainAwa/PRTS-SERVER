/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.fixes;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_FullActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Paper: 被活塞/黏液块推动的实体强制唤醒，防止物品卡住。
@Mixin(value = PistonMovingBlockEntity.class, priority = 900)
public class PistonMovingBlockEntityMixin_ActivationRange {

    @Redirect(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private static void activationRange$onPushEntity(Entity entity, double x, double y, double z,
                                                     Level level, BlockPos pos, float progress, PistonMovingBlockEntity piston) {
        entity.setDeltaMovement(x, y, z);
        if (!ServerCoreConfig.isActivationRangeEnabled()) return;
        final MinecraftServer server = level.getServer();
        if (server != null) {
            final int ticks = server.getTickCount() + 10;
            final EntityBridge_FullActivationRange bridge = (EntityBridge_FullActivationRange) entity;
            bridge.bridge$setActivatedTick(Math.max(bridge.bridge$getActivatedTick(), ticks));
            bridge.bridge$setActivatedImmunityTick(Math.max(bridge.bridge$getActivatedImmunityTick(), ticks));
        }
    }
}
