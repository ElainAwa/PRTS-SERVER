package io.izzel.arclight.common.mod.command.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.optimization.mpem.MemoryOptimizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

// Garbage Collection subcommand

public class GCSubCommand implements LuminaraSubCommand {

    @Override
    public String getName() {
        return "gc";
    }

    @Override
    public String getDescription() {
        return "Force garbage collection and memory cleanup";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(getName())
                .requires(source -> source.hasPermission(getRequiredPermissionLevel()))
                .executes(this::execute);
    }

    private int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        try {
            double beforeUsage = MemoryOptimizer.getMemoryUsage();
            MemoryOptimizer.forceCleanup();
            System.gc();

            // Wait a moment for GC to complete
            Thread.sleep(1000);

            double afterUsage = MemoryOptimizer.getMemoryUsage();
            double freed = (beforeUsage - afterUsage) * 100;

            source.sendSuccess(() -> Component.literal(String.format("Memory cleanup completed. Freed: %.2f%%", freed)), true);
            source.sendSuccess(() -> Component.literal(String.format("Memory usage: %.2f%% -> %.2f%%", beforeUsage * 100, afterUsage * 100)), false);

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to perform memory cleanup: " + e.getMessage()));
            return 0;
        }
    }
}
