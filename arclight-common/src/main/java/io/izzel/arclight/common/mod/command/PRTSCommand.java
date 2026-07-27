package io.izzel.arclight.common.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.mod.command.subcommands.InfoSubCommand;
import io.izzel.arclight.common.mod.command.subcommands.PRTSSubCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Main PRTS command system (loader-agnostic; each loader hooks its own
// command registration event and calls register(dispatcher))
public class PRTSCommand {

    private static final List<PRTSSubCommand> subCommands = new ArrayList<>();

    static {
        // Register default subcommands
        registerSubCommand(new InfoSubCommand());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("prts")
                .requires(source -> source.hasPermission(2)) // Lower base permission
                .executes(PRTSCommand::showHelp)
                .then(Commands.literal("help")
                        .executes(PRTSCommand::showHelp));

        // Register all subcommands
        for (PRTSSubCommand subCommand : subCommands) {
            if (subCommand.isEnabled()) {
                command.then(subCommand.build());
            }
        }

        dispatcher.register(command);
    }

    public static void registerSubCommand(PRTSSubCommand subCommand) {
        subCommands.add(subCommand);
    }

    private static int showHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        source.sendSuccess(() -> Component.literal("§6=== PRTS Commands ==="), false);
        source.sendSuccess(() -> Component.literal("§7PRTS - Enhanced Minecraft server platform"), false);
        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§e/prts help §7- Show this help"), false);

        for (PRTSSubCommand subCommand : subCommands) {
            if (subCommand.isEnabled()) {
                String permissionInfo = subCommand.getRequiredPermissionLevel() > 2 ? " §c[OP]" : "";
                source.sendSuccess(() -> Component.literal("§e/prts " + subCommand.getName() + permissionInfo + " §7- " + subCommand.getDescription()), false);
            }
        }

        source.sendSuccess(() -> Component.literal(""), false);
        source.sendSuccess(() -> Component.literal("§7Use §e/prts <command> §7for more details"), false);
        source.sendSuccess(() -> Component.literal("§6========================"), false);
        return 1;
    }

}
