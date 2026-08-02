package io.izzel.arclight.common.optimization.general.servercore.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * /statistics 命令（移植自 ServerCore StatisticsCommand）。
 * 偏差：去掉可配置模板与可点击翻页，分页/排序内联实现。
 */
public final class StatisticsCommand {

    private static final int PAGE_SIZE = 8;

    private StatisticsCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!ServerCoreConfig.commands().commandsEnabled()) return;
        if (!ServerCoreConfig.optimizations().statisticsCommandEnabled()) return;

        dispatcher.register(literal("statistics")
                .requires(Permission.require("command.statistics", 2))
                .executes(ctx -> overview(ctx.getSource()))
                .then(section("entities", false))
                .then(section("block-entities", true))
        );
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> section(String name, boolean blockEntity) {
        return literal(name)
                .executes(ctx -> display(ctx, blockEntity, false, 1))
                .then(argument("page", integer(1))
                        .executes(ctx -> display(ctx, blockEntity, false, getInteger(ctx, "page"))))
                .then(literal("byType")
                        .executes(ctx -> display(ctx, blockEntity, false, 1))
                        .then(argument("page", integer(1))
                                .executes(ctx -> display(ctx, blockEntity, false, getInteger(ctx, "page")))))
                .then(literal("byPlayer")
                        .executes(ctx -> display(ctx, blockEntity, true, 1))
                        .then(argument("page", integer(1))
                                .executes(ctx -> display(ctx, blockEntity, true, getInteger(ctx, "page")))));
    }

    private static int overview(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        // 直接取服务器均帧：dynamic 关闭时 DynamicManager 为 null，不可依赖
        double mspt = server.getAverageTickTime();
        double tps = mspt > 0 ? Math.min(1000 / mspt, 20) : 20;

        String text = "<c:#tertiary>Statistics\n"
                + "<c:#primary>TPS: <c:#secondary>" + String.format(Locale.ROOT, "%.2f", tps)
                + " <c:#primary>| MSPT: <c:#secondary>" + String.format(Locale.ROOT, "%.2f", mspt) + "\n"
                + "<c:#primary>Chunks: <c:#secondary>" + Statistics.chunkCount(server) + "\n"
                + "<c:#primary>Entities: <c:#secondary>" + Statistics.allEntities(server).size() + "\n"
                + "<c:#primary>Block entities: <c:#secondary>" + Statistics.allBlockEntities(server).size();

        source.sendSuccess(() -> Formatter.parse(text, server), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int display(CommandContext<CommandSourceStack> ctx, boolean blockEntity, boolean byPlayer, int page) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer self = source.getEntity() instanceof ServerPlayer p ? p : null;

        Map<String, Integer> map;
        if (byPlayer) {
            map = blockEntity
                    ? Statistics.blockEntitiesByPlayer(server.getPlayerList().getPlayers())
                    : Statistics.entitiesByPlayer(server.getPlayerList().getPlayers());
        } else if (blockEntity) {
            map = Statistics.blockEntitiesByType(self == null
                    ? Statistics.allBlockEntities(server) : Statistics.blockEntitiesNear(self));
        } else {
            map = Statistics.entitiesByType(self == null
                    ? Statistics.allEntities(server) : Statistics.entitiesNear(self));
        }

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(map.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        int pageCount = Math.max(1, (sorted.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (sorted.isEmpty()) {
            source.sendFailure(Component.literal(blockEntity ? "No block entities were found!" : "No entities were found!"));
            return 0;
        }
        if (page > pageCount) {
            source.sendFailure(Component.literal("Page doesn't exist!"));
            return 0;
        }

        StringBuilder sb = new StringBuilder("<c:#tertiary>")
                .append(blockEntity ? "Block Entities" : "Entities")
                .append(" by ").append(byPlayer ? "Player" : "Type");
        if (!byPlayer && self != null) {
            sb.append(" <c:#primary>(near ").append(self.getScoreboardName()).append(')');
        }

        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, sorted.size());
        for (int i = from; i < to; i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            sb.append("\n<c:#primary>").append(i + 1).append(". <c:#secondary>")
                    .append(entry.getKey()).append(" <c:#primary>- <c:#secondary>").append(entry.getValue());
        }
        sb.append("\n<c:#primary>Page <c:#secondary>").append(page)
                .append(" <c:#primary>/ <c:#secondary>").append(pageCount);

        String text = sb.toString();
        source.sendSuccess(() -> Formatter.parse(text, server), false);
        return Command.SINGLE_SUCCESS;
    }
}
