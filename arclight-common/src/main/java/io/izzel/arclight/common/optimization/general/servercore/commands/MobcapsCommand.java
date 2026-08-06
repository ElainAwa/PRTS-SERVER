/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.LocalMobCapCalculatorAccessor;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.MobCountsAccessor;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.SpawnStateAccessor;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
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
 * 1.20.1 无 mob_spawning 包，故标题去掉 MOBCAP_PERCENTAGE；私有字段走 accessor mixin。
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
            Component title = Formatter.parse("<c:#tertiary>Mobcaps", source.getServer());
            Formatter.addLines(component, 16, config.primaryValue(), title);

            Object2IntMap<MobCategory> counts = lookupCounts(player);
            if (counts != null) {
                for (MobCategory category : MobCategory.values()) {
                    if (category != MobCategory.MISC) {
                        component.append(Formatter.parse(String.format("\n<dark_gray>\u00bb <c:#primary>%s: <c:#secondary>%d <c:#primary>/ <c:#secondary>%d",
                                category.getName(),
                                counts.getOrDefault(category, 0),
                                category.getMaxInstancesPerChunk()
                        ), source.getServer()));
                    }
                }
            }
            return component;
        }, false);
        return Command.SINGLE_SUCCESS;
    }

    // SpawnState.localMobCapCalculator / playerMobCounts / MobCounts.counts 在 1.20.1 均为私有。
    private static Object2IntMap<MobCategory> lookupCounts(ServerPlayer player) {
        NaturalSpawner.SpawnState state = player.serverLevel().getChunkSource().getLastSpawnState();
        if (state == null) return null;
        LocalMobCapCalculator calculator = ((SpawnStateAccessor) state).arclight$localMobCapCalculator();
        if (calculator == null) return null;
        Object mobCounts = ((LocalMobCapCalculatorAccessor) calculator).arclight$playerMobCounts().get(player);
        return mobCounts == null ? null : ((MobCountsAccessor) mobCounts).arclight$counts();
    }
}
