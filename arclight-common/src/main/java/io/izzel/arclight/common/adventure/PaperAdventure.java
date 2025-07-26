package io.izzel.arclight.common.adventure;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

// Adventure integration for Luminara
public final class PaperAdventure {

    private static final GsonComponentSerializer GSON_SERIALIZER = GsonComponentSerializer.gson();
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN_SERIALIZER = PlainTextComponentSerializer.plainText();
    private static final MiniMessage MINI_MESSAGE = createMiniMessage();

    private static MiniMessage createMiniMessage() {
        return MiniMessage.builder().build();
    }

    private PaperAdventure() {
        throw new RuntimeException("PaperAdventure is not to be instantiated!");
    }

    // Convert Adventure Component to Minecraft Component
    public static net.minecraft.network.chat.Component asVanilla(@NotNull Component component) {
        return net.minecraft.network.chat.Component.Serializer.fromJson(GSON_SERIALIZER.serialize(component));
    }

    // Convert Minecraft Component to Adventure Component
    public static @NotNull Component asAdventure(@NotNull net.minecraft.network.chat.Component component) {
        return GSON_SERIALIZER.deserialize(net.minecraft.network.chat.Component.Serializer.toJson(component));
    }

    // Convert legacy string to Adventure Component
    public static @NotNull Component legacyToAdventure(@NotNull String legacy) {
        return LEGACY_SERIALIZER.deserialize(legacy);
    }

    // Convert Adventure Component to legacy string
    public static @NotNull String adventureToLegacy(@NotNull Component component) {
        return LEGACY_SERIALIZER.serialize(component);
    }

    // Convert Adventure Component to plain text
    public static @NotNull String asPlain(@NotNull Component component) {
        return PLAIN_SERIALIZER.serialize(component);
    }

    // Convert MiniMessage string to Adventure Component
    public static @NotNull Component miniMessageToAdventure(@NotNull String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage);
    }

    // Convert Adventure Component to MiniMessage string
    public static @NotNull String adventureToMiniMessage(@NotNull Component component) {
        return MINI_MESSAGE.serialize(component);
    }

    // Get MiniMessage instance
    public static @NotNull MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }

    // Resolve component with context
    public static @NotNull Component resolveWithContext(@NotNull Component input, @Nullable CommandSender context, @Nullable Entity scoreboardSubject, boolean bypassPermissions) throws IOException {
        // TODO: Implement proper context resolution
        return input;
    }

    // Get all audiences from command senders
    public static @NotNull List<Audience> audiences(@NotNull List<? extends CommandSender> senders) {
        return senders.stream()
                .filter(sender -> sender instanceof Audience)
                .map(sender -> (Audience) sender)
                .toList();
    }

    // Create unsigned SignedMessage
    public static @NotNull SignedMessage createUnsignedMessage(@NotNull String content) {
        return SimpleSignedMessage.unsigned(content);
    }

    // Create signed SignedMessage
    public static @NotNull SignedMessage createSignedMessage(@NotNull String content, @NotNull UUID sender) {
        return SimpleSignedMessage.signed(content, sender);
    }
}
