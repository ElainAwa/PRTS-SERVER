package io.izzel.arclight.common.optimization.general.servercore.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicManager;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /servercore 根命令（移植自 ServerCore ServerCoreCommand）。
 * 子命令：reload / settings &lt;动态项&gt; &lt;值&gt; / status；sc 为别名重定向。
 */
public class ServerCoreCommands {
    public static final String VERSION = "PRTS-1.21.1";
    private static final String VALUE = "value";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var node = literal("servercore");

        node.then(reloadConfig());
        node.then(settings());

        if (ServerCoreConfig.commands().statusCommandEnabled()) {
            node.then(literal("status").executes(ctx -> getStatus(ctx.getSource())));
        }

        dispatcher.register(node);
        dispatcher.register(literal("sc").redirect(node.build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> reloadConfig() {
        return literal("reload")
                .requires(Permission.require("command.config", 2))
                .executes(ctx -> reload(ctx.getSource()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settings() {
        var settings = literal("settings").requires(Permission.require("command.settings", 2));
        for (DynamicSetting setting : DynamicSetting.values()) {
            settings.then(literal(setting.name().toLowerCase())
                    .then(argument(VALUE, integer(setting.getLowerBound(), setting.getUpperBound()))
                            .executes(ctx -> modifyDynamic(ctx.getSource(), getInteger(ctx, VALUE), setting))
                    )
            );
        }
        return settings;
    }

    private static int modifyDynamic(CommandSourceStack source, int value, DynamicSetting setting) {
        DynamicManager manager = DynamicManager.getInstance(source.getServer());
        setting.set(value, manager);
        source.sendSuccess(() -> Formatter.parse("<c:#secondary>%s <c:#primary>has been set to <c:#secondary>%s".formatted(
                setting.getFormattedName(), setting.getFormattedValue()
        ), source.getServer()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandSourceStack source) {
        boolean success = ServerCoreConfig.reload();
        if (success) {
            source.sendSuccess(() -> Component.literal("Config reloaded!").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendFailure(Component.literal("Failed to reload config! Check the logs for more info.").withStyle(ChatFormatting.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int getStatus(CommandSourceStack source) {
        CommandConfig config = ServerCoreConfig.commands();
        source.sendSuccess(() -> {
            MutableComponent component = Component.empty();
            Component title = Component.literal("ServerCore").withColor(config.tertiaryValue());
            if (source.isPlayer()) {
                Formatter.addLines(component, 14, config.primaryValue(), title);
            } else {
                component.append(title);
            }

            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>Version: <c:#secondary>%s".formatted(
                    VERSION
            ), source.getServer()));

            for (DynamicSetting setting : DynamicSetting.values()) {
                component.append(Formatter.parse("\n<dark_gray>» <c:#primary>%s: <c:#secondary>%s".formatted(
                        setting.getFormattedName(), setting.getFormattedValue()
                ), source.getServer()));
            }
            return component;
        }, false);
        return Command.SINGLE_SUCCESS;
    }
}
