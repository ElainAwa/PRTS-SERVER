/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.commands;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令反馈文本格式化（移植自 ServerCore Formatter）。
 * 自写极简解析器，绕开上游对 adventure MiniMessage 的依赖。
 */
public class Formatter {
    private static final Pattern TAG = Pattern.compile("<(c:#[0-9a-fA-F]{6}|[a-z_]+)>");

    public static Component parse(String input, MinecraftServer server) {
        CommandConfig config = ServerCoreConfig.commands();
        String replaced = input
                .replace("#primary", config.primaryHex())
                .replace("#secondary", config.secondaryHex())
                .replace("#tertiary", config.tertiaryHex());
        return parseTags(replaced);
    }

    private static Component parseTags(String s) {
        MutableComponent out = Component.literal("");
        Matcher m = TAG.matcher(s);
        int last = 0;
        Style style = Style.EMPTY;
        while (m.find()) {
            if (m.start() > last) {
                out.append(Component.literal(s.substring(last, m.start())).withStyle(style));
            }
            String token = m.group(1);
            if (token.startsWith("c:#")) {
                style = style.withColor(TextColor.fromRgb(Integer.parseInt(token.substring(3), 16)));
            } else {
                ChatFormatting cf = ChatFormatting.getByName(token);
                if (cf != null) style = style.withColor(cf);
            }
            last = m.end();
        }
        if (last < s.length()) {
            out.append(Component.literal(s.substring(last)).withStyle(style));
        }
        return out;
    }

    // 1.20.1 无 MutableComponent.withColor(int)，改走 Style。
    public static void addLines(MutableComponent out, int lineLength, int lineColor, Component component) {
        Component line = Component.literal(" ".repeat(lineLength))
                .withStyle(Style.EMPTY.withColor(lineColor).withStrikethrough(true));
        out.append(line);
        out.append(" ");
        out.append(component);
        out.append(" ");
        out.append(line);
    }
}
