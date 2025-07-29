package io.izzel.arclight.common.mod.command.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.adventure.PaperAdventure;
import io.izzel.arclight.common.bridge.core.command.CommandSourceBridge;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.bukkit.command.CommandSender;

// Adventure message format testing
public class AdventureTestSubCommand implements LuminaraSubCommand {

    @Override
    public String getName() {
        return "testadventure";
    }

    @Override
    public String getDescription() {
        return "Test Adventure message formats (MiniMessage, Legacy, etc.)";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(getName())
                .requires(source -> source.hasPermission(getRequiredPermissionLevel()))
                .executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        CommandSender sender = ((CommandSourceBridge) source).bridge$getBukkitSender();

        try {
            sender.sendMessage("§6=== Adventure Message Format Test ===");

            // Test 1: Plain text
            sender.sendMessage("§e1. Plain Text:");
            sender.sendMessage("This is a plain text message");

            // Test 2: Legacy format
            sender.sendMessage("§e2. Legacy Format:");
            sender.sendMessage("§aGreen text §bwith §ccolors §dand §eformatting");
            sender.sendMessage("§l§nBold and underlined text");

            // Test 3: Adventure Component (direct)
            sender.sendMessage("§e3. Adventure Component (Direct):");
            if (sender instanceof net.kyori.adventure.audience.Audience) {
                net.kyori.adventure.text.Component adventureMsg = net.kyori.adventure.text.Component.text("This is an Adventure Component")
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD);
                ((net.kyori.adventure.audience.Audience) sender).sendMessage(adventureMsg);
            } else {
                sender.sendMessage("§c[Adventure not supported for this sender type]");
            }

            // Test 4: MiniMessage format
            sender.sendMessage("§e4. MiniMessage Format:");
            testMiniMessage(sender, "<green>Green text</green>");
            testMiniMessage(sender, "<red><bold>Bold red text</bold></red>");
            testMiniMessage(sender, "<gradient:blue:green>Gradient text</gradient>");
            testMiniMessage(sender, "<rainbow>Rainbow text</rainbow>");
            testMiniMessage(sender, "<click:run_command:/help>Click me!</click>");
            testMiniMessage(sender, "<hover:show_text:'Hover tooltip'>Hover over me</hover>");

            // Test 5: Mixed formats
            sender.sendMessage("§e5. Mixed Format Detection:");
            testMessageParsing(sender, "<green>MiniMessage</green>");
            testMessageParsing(sender, "§aLegacy format");
            testMessageParsing(sender, "&aLegacy ampersand format");
            testMessageParsing(sender, "Plain text");

            sender.sendMessage("§6=== Test Complete ===");

            source.sendSuccess(() -> Component.literal("Adventure message format test completed"), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to run adventure test: " + e.getMessage()));
            return 0;
        }
    }

    private void testMiniMessage(CommandSender sender, String miniMessage) {
        try {
            net.kyori.adventure.text.Component component = PaperAdventure.miniMessageToAdventure(miniMessage);
            sender.sendMessage("§7Input: §f" + miniMessage);
            if (sender instanceof net.kyori.adventure.audience.Audience) {
                ((net.kyori.adventure.audience.Audience) sender).sendMessage(component);
            } else {
                String legacy = PaperAdventure.adventureToLegacy(component);
                sender.sendMessage("§7Fallback: " + legacy);
            }
        } catch (Exception e) {
            sender.sendMessage("§cFailed to parse MiniMessage: " + miniMessage);
            sender.sendMessage("§cError: " + e.getMessage());
        }
    }


    private void testMessageParsing(CommandSender sender, String message) {
        try {
            net.kyori.adventure.text.Component component = PaperAdventure.parseMessage(message);
            sender.sendMessage("§7Input: §f" + message);
            if (sender instanceof net.kyori.adventure.audience.Audience) {
                ((net.kyori.adventure.audience.Audience) sender).sendMessage(component);
            } else {
                String legacy = PaperAdventure.adventureToLegacy(component);
                sender.sendMessage("§7Parsed: " + legacy);
            }
        } catch (Exception e) {
            sender.sendMessage("§cFailed to parse message: " + message);
            sender.sendMessage("§cError: " + e.getMessage());
        }
    }
}
