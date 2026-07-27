package io.izzel.arclight.common.mod.command.subcommands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

/**
 * PRTS subcommand interface
 */
public interface PRTSSubCommand {

    String getName();

    String getDescription();

    LiteralArgumentBuilder<CommandSourceStack> build();

    default int getRequiredPermissionLevel() {
        return 3;
    }

    default boolean isEnabled() {
        return true;
    }
}
