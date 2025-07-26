package io.izzel.arclight.common.optimization.mpem;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class OptimizationCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("luminara")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("gc")
                        .executes(OptimizationCommand::forceGC))
                .then(Commands.literal("cleanup")
                        .executes(OptimizationCommand::forceEntityCleanup)
                        .then(Commands.literal("cancel")
                                .executes(OptimizationCommand::cancelEntityCleanup))));
    }

    private static int forceGC(CommandContext<CommandSourceStack> context) {
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

    private static int forceEntityCleanup(CommandContext<CommandSourceStack> context) {
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

    private static int cancelEntityCleanup(CommandContext<CommandSourceStack> context) {
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
