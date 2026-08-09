/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_ActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.activation_range.ActivationRange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

// Ported from Wesley1808/ServerCore;MixinExtras 不可用，@WrapWithCondition 改为原生 @Inject/@Redirect。
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_ActivationRange {

    // @formatter:off
    @Shadow @Final private MinecraftServer server;
    // @formatter:on

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void activationRange$activateEntities(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return;
        final int currentTick = this.server.getTickCount();
        if (currentTick % 20 == 0) {
            ActivationRange.activateEntities((ServerLevel) (Object) this, currentTick);
        }
    }

    // 非激活实体跳过整个 tick，改走轻量的 inactiveTick
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void activationRange$tickNonPassenger(Entity entity, CallbackInfo ci) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return;
        final EntityBridge_ActivationRange bridge = (EntityBridge_ActivationRange) entity;
        bridge.bridge$incFullTickCount();
        if (ActivationRange.checkIfActive(entity, this.server.getTickCount())) {
            bridge.bridge$setInactive(false);
        } else {
            bridge.bridge$setInactive(true);
            bridge.bridge$inactiveTick();
            ci.cancel();
        }
    }

    // 乘客的 tickCount 只在真正被 tick 时推进，全量计数另存，避免破坏实体行为
    @Redirect(method = "tickPassenger", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/Entity;tickCount:I"))
    private void activationRange$redirectPassengerTickCount(Entity passenger, int value) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) {
            passenger.tickCount = value;
            return;
        }
        ((EntityBridge_ActivationRange) passenger).bridge$incFullTickCount();
    }

    @Inject(method = "tickPassenger", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
    private void activationRange$tickPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return;
        final EntityBridge_ActivationRange bridge = (EntityBridge_ActivationRange) passenger;
        if (ActivationRange.checkIfActive(passenger, this.server.getTickCount())) {
            bridge.bridge$setInactive(false);
            passenger.tickCount++;
        } else {
            passenger.setDeltaMovement(Vec3.ZERO);
            bridge.bridge$setInactive(true);
            bridge.bridge$inactiveTick();
            vehicle.positionRider(passenger);
            ci.cancel();
        }
    }
}
