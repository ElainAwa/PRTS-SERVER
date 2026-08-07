package io.izzel.arclight.common.optimization.general.servercore.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;

import static net.minecraft.commands.Commands.literal;

/**
 * /mobcaps 命令（移植自 ServerCore MobcapsCommand）。
 * 显示玩家附近的各类生物当前计数 / 上限，依赖 Phase 2 暴露的 LocalMobCapCalculator 字段。
 * 注：LocalMobCapCalculator.MobCounts 构造非公开，无法构造空实例；玩家无计数记录时跳过。
 */
public class MobcapsCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!ServerCoreConfig.commands().commandsEnabled()) return;
        if (ServerCoreConfig.commands().mobcapsCommandEnabled()) {
            dispatcher.register(literal("mobcaps").executes(ctx -> mobcaps(ctx.getSource(), ctx.getSource().getPlayerOrException())));
        }
    }

    private static int mobcaps(CommandSourceStack source, ServerPlayer player) {
        CommandConfig config = ServerCoreConfig.commands();
        source.sendSuccess(() -> {
            MutableComponent component = Component.empty();

            Component title = Formatter.parse("<c:#tertiary>Mobcaps <c:#primary>(<c:#tertiary>%s</c>)".formatted(
                    DynamicSetting.MOBCAP_PERCENTAGE.getFormattedValue()
            ), source.getServer());

            Formatter.addLines(component, 16, config.primaryValue(), title);

            NaturalSpawner.SpawnState state = player.serverLevel().getChunkSource().getLastSpawnState();
            if (state != null) {
                LocalMobCapCalculator.MobCounts mobCounts = state.localMobCapCalculator.playerMobCounts.get(player);
                if (mobCounts != null) {
                    for (MobCategory category : MobCategory.values()) {
                        if (category != MobCategory.MISC) {
                            component.append(Formatter.parse("\n<dark_gray>» <c:#primary>%s:</c> <c:#secondary>%d</c> / <c:#secondary>%d".formatted(
                                    category.getName(),
                                    mobCounts.counts.getOrDefault(category, 0),
                                    category.getMaxInstancesPerChunk()
                            ), source.getServer()));
                        }
                    }
                }
            }
            return component;
        }, false);
        return Command.SINGLE_SUCCESS;
    }
}
