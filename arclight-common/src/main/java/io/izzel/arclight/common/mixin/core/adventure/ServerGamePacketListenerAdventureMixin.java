package io.izzel.arclight.common.mixin.core.adventure;

import io.izzel.arclight.common.adventure.PaperAdventure;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerAdventureMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "broadcastChatMessage", at = @At("HEAD"), cancellable = true)
    private void arclight$handleAdventureChat(PlayerChatMessage playerChatMessage, CallbackInfo ci) {

        String content = playerChatMessage.signedContent();
        Component message = Component.text(content);
        Component originalMessage = message;


        SignedMessage signedMessage = PaperAdventure.createUnsignedMessage(content);


        Player bukkitPlayer = (Player) ((io.izzel.arclight.common.bridge.core.entity.player.ServerPlayerEntityBridge) player).bridge$getBukkitEntity();


        Set<Audience> viewers = new HashSet<>();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer instanceof Audience audience) {
                viewers.add(audience);
            }
        }


        ChatRenderer renderer = ChatRenderer.defaultRenderer();


        AsyncChatEvent event = new AsyncChatEvent(
                true, // async
                bukkitPlayer,
                viewers,
                renderer,
                message,
                originalMessage,
                signedMessage
        );

        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            ci.cancel();
            return;
        }


        Component finalMessage = event.message();
        ChatRenderer finalRenderer = event.renderer();
        Component displayName = Component.text(bukkitPlayer.getDisplayName());

        for (Audience viewer : event.viewers()) {
            Component rendered = finalRenderer.render(bukkitPlayer, displayName, finalMessage, viewer);
            viewer.sendMessage(rendered);
        }


        ci.cancel();
    }
}
