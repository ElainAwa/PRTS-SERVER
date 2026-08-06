/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.features.misc;

import com.llamalad7.mixinextras.sugar.Local;
import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.Set;

/**
 * 玩家/载具移动进未加载区块时回弹，避免同步加载卡顿（源自 Paper 补丁，经 ServerCore 移植）。
 * 上游用 LocalCapture.CAPTURE_FAILHARD，在 NeoForge 1.21.1 的 handleMovePlayer 会因活跃局部变量
 * 数量不符而注入失败；此处改用 mixinextras @Local 按类型序号精确取值。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin extends ServerCommonPacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    public ServerGamePacketListenerImplMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Shadow
    public abstract void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet);

    @Inject(
            method = "handleMoveVehicle",
            cancellable = true,
            require = 0,
            expect = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;lengthSqr()D",
                    shift = At.Shift.AFTER,
                    ordinal = 0
            )
    )
    private void luminara$handleMoveVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci,
                                            @Local Entity entity,
                                            @Local ServerLevel serverLevel,
                                            @Local(ordinal = 0) double fromX,
                                            @Local(ordinal = 2) double fromZ,
                                            @Local(ordinal = 3) double toX,
                                            @Local(ordinal = 4) double toY,
                                            @Local(ordinal = 5) double toZ) {
        if (this.luminara$shouldPreventMovement(serverLevel, entity, fromX, fromZ, toX, toY, toZ)) {
            this.connection.send(new ClientboundMoveVehiclePacket(entity));
            ci.cancel();
        }
    }

    @Inject(
            method = "handleMovePlayer",
            cancellable = true,
            require = 0,
            expect = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;getBoundingBox()Lnet/minecraft/world/phys/AABB;",
                    ordinal = 0
            )
    )
    private void luminara$handleMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci,
                                           @Local ServerLevel serverLevel,
                                           @Local(ordinal = 0) double toX,
                                           @Local(ordinal = 1) double toY,
                                           @Local(ordinal = 2) double toZ,
                                           @Local(ordinal = 3) double fromX,
                                           @Local(ordinal = 4) double fromY,
                                           @Local(ordinal = 5) double fromZ,
                                           @Local(ordinal = 0) float yRot,
                                           @Local(ordinal = 1) float xRot) {
        if (this.luminara$shouldPreventMovement(serverLevel, this.player, fromX, fromZ, toX, toY, toZ)) {
            this.teleport(fromX, fromY, fromZ, yRot, xRot, Collections.emptySet());
            ci.cancel();
        }
    }

    @Unique
    private boolean luminara$shouldPreventMovement(ServerLevel level, Entity entity, double fromX, double fromZ, double toX, double toY, double toZ) {
        return ServerCoreConfig.features().preventMovingIntoUnloadedChunks()
                && (fromX != toX || fromZ != toZ)
                && !ChunkManager.areChunksLoadedForMove(level, entity.getBoundingBox().expandTowards(new Vec3(toX, toY, toZ).subtract(entity.position())));
    }
}
