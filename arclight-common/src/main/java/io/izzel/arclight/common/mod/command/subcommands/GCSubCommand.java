package io.izzel.arclight.common.mod.command.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
        return "Force garbage collection";
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
            Runtime runtime = Runtime.getRuntime();
            long beforeGC = runtime.totalMemory() - runtime.freeMemory();
            
            System.gc();
            Thread.sleep(1000);

            long afterGC = runtime.totalMemory() - runtime.freeMemory();
            long freed = beforeGC - afterGC;
            double freedMB = freed / (1024.0 * 1024.0);

            source.sendSuccess(() -> Component.literal(String.format("Garbage collection completed. Freed: %.2f MB", freedMB)), true);
            source.sendSuccess(() -> Component.literal(String.format("Memory usage: %.2f MB -> %.2f MB",
                    beforeGC / (1024.0 * 1024.0), afterGC / (1024.0 * 1024.0))), false);

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to perform garbage collection: " + e.getMessage()));
            return 0;
        }
    }
}
