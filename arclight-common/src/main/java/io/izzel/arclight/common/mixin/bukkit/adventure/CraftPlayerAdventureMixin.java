package io.izzel.arclight.common.mixin.bukkit.adventure;

import io.izzel.arclight.common.adventure.PaperAdventure;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import org.bukkit.craftbukkit.v.entity.CraftPlayer;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CraftPlayer.class, remap = false)
public abstract class CraftPlayerAdventureMixin implements Audience {


    @Override
    public void sendMessage(@NotNull Component message) {
        CraftPlayer player = (CraftPlayer) (Object) this;
        // Convert Adventure Component to Minecraft Component and send directly
        net.minecraft.network.chat.Component vanillaComponent = PaperAdventure.asVanilla(message);
        player.getHandle().sendSystemMessage(vanillaComponent);
    }

    @Override
    public void sendMessage(@NotNull Component message, net.kyori.adventure.audience.MessageType type) {

        sendMessage(message);
    }

    @Override
    public void sendMessage(@NotNull Identity source, @NotNull Component message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(@NotNull Identity source, @NotNull Component message, net.kyori.adventure.audience.MessageType type) {
        sendMessage(message);
    }


    public @NotNull Identity identity() {
        return Identity.identity(((CraftPlayer) (Object) this).getUniqueId());
    }


    public @NotNull Component displayName() {
        return Component.text(((CraftPlayer) (Object) this).getName());
    }

    public void displayName(@NotNull Component displayName) {
        // TODO: Implement display name setting
    }
}
