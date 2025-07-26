package io.izzel.arclight.common.mod.command.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.optimization.mpem.EntityCleaner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

// Entity cleanup subcommand

public class CleanupSubCommand implements LuminaraSubCommand {
    
    @Override
    public String getName() {
        return "cleanup";
    }
    
    @Override
    public String getDescription() {
        return "Force entity cleanup or cancel scheduled cleanup";
    }
    
    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(getName())
                .requires(source -> source.hasPermission(getRequiredPermissionLevel()))
                .executes(this::executeCleanup)
                .then(Commands.literal("cancel")
                        .executes(this::executeCancel));
    }
    
    private int executeCleanup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            EntityCleaner.forceCleanup(source.getServer());
            source.sendSuccess(() -> Component.literal("Entity cleanup completed successfully"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to perform entity cleanup: " + e.getMessage()));
            return 0;
        }
    }
    
    private int executeCancel(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            EntityCleaner.cancelScheduledCleanup(source.getServer());
            source.sendSuccess(() -> Component.literal("Scheduled entity cleanup cancelled"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to cancel entity cleanup: " + e.getMessage()));
            return 0;
        }
    }
}
