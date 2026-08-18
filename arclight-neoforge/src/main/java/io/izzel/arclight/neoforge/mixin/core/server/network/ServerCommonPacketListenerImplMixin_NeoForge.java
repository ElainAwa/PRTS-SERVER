package io.izzel.arclight.neoforge.mixin.core.server.network;

import io.izzel.arclight.common.bridge.core.server.network.ServerCommonPacketListenerImplBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * TACZ / SkinsRestorer compatibility fix (see docs/2026-08-18-tacz-skin-respawn-fix.md).
 *
 * <p>When a Bukkit plugin like SkinsRestorer refreshes a player's skin it pushes a
 * {@link ClientboundRespawnPacket} (the "fake respawn" trick) to the client. On 1.21.1 the
 * vanilla client no longer calls {@code LocalPlayer#respawn()} for a same-level, keep-data
 * respawn, so TACZ's own client-side recovery hook
 * ({@code LocalPlayerMixin} targeting {@code LocalPlayer.respawn()}) never runs. If the skin
 * refresh races a shot / leaves the player's gun-operator lock held, the synced per-player
 * cooldowns TACZ reads on the client stay stale and the gun can no longer fire until the player
 * re-equips or relogs.
 *
 * <p>We repair this strictly server-side: whenever a respawn packet is pushed to a player we
 * re-initialise that player's TACZ gun operator through the public {@code IGunOperator.initialData()}
 * interface (reflection — no TACZ source changes). This collapses the synced shoot / draw / sprint
 * cooldowns to 0 and re-asserts the drawn gun, so the client's fire state lock releases and firing
 * resumes. A real respawn goes through the same branch, which is exactly the recovery TACZ intended.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin_NeoForge implements ServerCommonPacketListenerImplBridge {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void arclight$resetGunOperatorOnRespawn(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundRespawnPacket)) {
            return;
        }
        // Only the game-play listener carries a ServerPlayer; configuration/status/handshake
        // listeners never send a ClientboundRespawnPacket, so this cast is always valid here.
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl listener) || listener.player == null) {
            return;
        }
        ServerPlayer player = listener.player;
        MinecraftServer server = player.getServer();
        // Packet send can originate off the main thread (e.g. plugin async); TACZ's per-entity
        // gun state is main-thread owned, so marshal the reset onto the server tick.
        Runnable runnable = () -> resetTaczGunOperator(player);
        if (server == null || server.isSameThread()) {
            runnable.run();
        } else {
            server.execute(runnable);
        }
    }

    /**
     * Re-initialises TACZ's gun-operator state on the given player via the public
     * {@code IGunOperator.initialData()} method. Uses reflection so arclight has no hard compile
     * dependency on TACZ; if the mod (or the method) is absent this is a no-op.
     */
    private static void resetTaczGunOperator(ServerPlayer player) {
        if (player == null || !player.isAlive()) {
            return;
        }
        try {
            Class<?> operatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            Method fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
            Object operator = fromLivingEntity.invoke(null, (LivingEntity) player);
            operatorClass.getMethod("initialData").invoke(operator);
        } catch (Throwable ignored) {
            // TACZ not installed or signature changed; nothing to repair on this server.
        }
    }
}