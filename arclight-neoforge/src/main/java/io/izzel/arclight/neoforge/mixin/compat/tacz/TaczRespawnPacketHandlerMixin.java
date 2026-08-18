package io.izzel.arclight.neoforge.mixin.compat.tacz;

import io.izzel.arclight.neoforge.compat.tacz.TaczGunOperatorCompat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TACZ / SkinsRestorer compatibility mixin.
 *
 * <p>When a Bukkit plugin such as SkinsRestorer refreshes a player's skin it pushes a fake
 * {@link ClientboundRespawnPacket} (keep-data) to the client — see
 * {@link TaczGunOperatorCompat} for why this breaks firing and how we repair it.
 *
 * <p>Injection point: every {@code ClientboundRespawnPacket} pushed to a player re-initialises that
 * player's TACZ gun operator and schedules a fresh sync, so a skin change (or a real respawn) never
 * leaves the client's gun operator stuck. A lightweight diagnostic also logs whenever TACZ's shoot
 * payload arrives, to tell server-side state from a purely client-side stall.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TaczRespawnPacketHandlerMixin {
    private static final Logger LOGGER = LogManager.getLogger("Arclight-TACZ");

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void arclight$resetGunOperatorOnRespawn(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundRespawnPacket)) {
            return;
        }
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return;
        }
        ServerPlayer player = listener.player;
        MinecraftServer server = player.getServer();
        Runnable runnable = () -> TaczGunOperatorCompat.resetAndResyncGunOperator(player);
        if (server == null || server.isSameThread()) {
            runnable.run();
        } else {
            server.execute(runnable);
        }
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void arclight$trackTaczShoot(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        try {
            if (!TaczGunOperatorCompat.SHOOT_CHANNEL.equals(packet.payload().type().id())) {
                return;
            }
            if (!(((Object) this) instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
                return;
            }
            ServerPlayer player = listener.player;
            LOGGER.info("[Arclight-TACZ] shoot-packet arrived player={} serverTacz={}",
                    player.getName().getString(), TaczGunOperatorCompat.snapshot(player));
        } catch (Throwable ignored) {
            // Diagnostic only; never disrupt packet handling.
        }
    }
}