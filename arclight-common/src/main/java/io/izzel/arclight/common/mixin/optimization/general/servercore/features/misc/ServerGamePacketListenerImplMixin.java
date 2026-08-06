/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.features.misc;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Set;

/**
 * Based on: Paper (Add-option-to-prevent-players-from-moving-into-unloaded-chunks.patch)
 * <p>
 * Patch Author: Gabriele C (sgdc3.mail@gmail.com)
 * <br>
 * License: GPL-3.0 (licenses/GPL.md)
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet);

    @Inject(method = "handleMoveVehicle", cancellable = true, at = @At("HEAD"))
    private void servercore$handleMoveVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        Entity entity = this.player.getVehicle();
        if (entity == null) return;
        ServerLevel serverLevel = (ServerLevel) entity.level();
        if (this.servercore$shouldPreventMovement(serverLevel, entity,
                entity.getX(), entity.getZ(),
                packet.getX(), packet.getY(), packet.getZ())) {
            this.connection.send(new ClientboundMoveVehiclePacket(entity));
            ci.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", cancellable = true, at = @At("HEAD"))
    private void servercore$handleMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        // Only position-carrying packets can move the player into unloaded chunks.
        if (!(packet instanceof ServerboundMovePlayerPacket.Pos) && !(packet instanceof ServerboundMovePlayerPacket.PosRot)) {
            return;
        }
        ServerLevel serverLevel = this.player.serverLevel();
        double toX = packet.getX(this.player.getX());
        double toY = packet.getY(this.player.getY());
        double toZ = packet.getZ(this.player.getZ());
        if (this.servercore$shouldPreventMovement(serverLevel, this.player,
                this.player.getX(), this.player.getZ(), toX, toY, toZ)) {
            this.teleport(this.player.getX(), this.player.getY(), this.player.getZ(),
                    packet.getYRot(this.player.getYRot()), packet.getXRot(this.player.getXRot()), Collections.emptySet());
            ci.cancel();
        }
    }

    private boolean servercore$shouldPreventMovement(ServerLevel level, Entity entity, double fromX, double fromZ, double toX, double toY, double toZ) {
        return ServerCoreConfig.features().preventMovingIntoUnloadedChunks()
               && (fromX != toX || fromZ != toZ)
               && !ChunkManager.areChunksLoadedForMove(level, entity.getBoundingBox().expandTowards(new Vec3(toX, toY, toZ).subtract(entity.position())));
    }
}
