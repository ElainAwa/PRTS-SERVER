package io.izzel.arclight.common.mixin.bukkit.adventure;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Merchant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = Bukkit.class, remap = false)
public class BukkitAdventureMixin {

    @Shadow
    private static Server server;


    public static int broadcast(net.kyori.adventure.text.@NotNull Component message) {
        return server.broadcastMessage(io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(message));
    }

    public static int broadcast(net.kyori.adventure.text.@NotNull Component message, @NotNull String permission) {
        return server.broadcast(io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(message), permission);
    }


    public static @NotNull Inventory createInventory(@Nullable InventoryHolder owner, @NotNull InventoryType type, net.kyori.adventure.text.@NotNull Component title) {
        return server.createInventory(owner, type, io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(title));
    }

    public static @NotNull Inventory createInventory(@Nullable InventoryHolder owner, int size, net.kyori.adventure.text.@NotNull Component title) throws IllegalArgumentException {
        return server.createInventory(owner, size, io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(title));
    }


    public static @NotNull Merchant createMerchant(net.kyori.adventure.text.@Nullable Component title) {
        return server.createMerchant(title == null ? null : io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(title));
    }


    public static net.kyori.adventure.text.@NotNull Component motd() {
        return io.izzel.arclight.common.adventure.PaperAdventure.legacyToAdventure(server.getMotd());
    }

    public static void motd(final net.kyori.adventure.text.@NotNull Component motd) {
        server.setMotd(io.izzel.arclight.common.adventure.PaperAdventure.adventureToLegacy(motd));
    }

    public static net.kyori.adventure.text.@Nullable Component shutdownMessage() {
        String message = server.getShutdownMessage();
        return message == null ? null : io.izzel.arclight.common.adventure.PaperAdventure.legacyToAdventure(message);
    }
}
