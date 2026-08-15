/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ownership;

import io.izzel.arclight.common.optimization.general.servercore.ownership.WorldAccessGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ServerLevel-only accessors that bypass the Level boundary mixin because they
 * are declared on ServerLevel (or only overridden there in the case of
 * levelEvent). Both are main-thread-only writes from the worker's point of
 * view.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin_OwnershipGuard {

    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"))
    private void arclight$guardAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        WorldAccessGuard.checkMainOnlyWrite((ServerLevel) (Object) this, entity.blockPosition());
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"))
    private void arclight$guardLevelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldAccessGuard.checkMainOnlyWrite((ServerLevel) (Object) this, pos);
    }
}
