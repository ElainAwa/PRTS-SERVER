/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.commands;

import net.minecraft.ChatFormatting;

/**
 * commands 配置段（移植自 ServerCore CommandConfig）。
 * 去 dazzleconf / TextColor：颜色存 hex 字符串，运行时解析为 int。
 */
public class CommandConfig {
    public static final CommandConfig DISABLED = new CommandConfig(false, false, false, "00aabb", "55ff55", "55ffff");

    private final boolean enabled;
    private final boolean statusEnabled;
    private final boolean mobcapsEnabled;
    private final String primaryColor;
    private final String secondaryColor;
    private final String tertiaryColor;

    public CommandConfig(boolean enabled, boolean statusEnabled, boolean mobcapsEnabled, String primaryColor, String secondaryColor, String tertiaryColor) {
        this.enabled = enabled;
        this.statusEnabled = statusEnabled;
        this.mobcapsEnabled = mobcapsEnabled;
        this.primaryColor = primaryColor;
        this.secondaryColor = secondaryColor;
        this.tertiaryColor = tertiaryColor;
    }

    // 真总开关：false 时整套 servercore/sc/mobcaps 命令都不注册
    public boolean commandsEnabled() {
        return enabled;
    }

    public boolean statusCommandEnabled() {
        return statusEnabled;
    }

    public boolean mobcapsCommandEnabled() {
        return mobcapsEnabled;
    }

    public String primaryHex() {
        return "#" + normalize(primaryColor);
    }

    public String secondaryHex() {
        return "#" + normalize(secondaryColor);
    }

    public String tertiaryHex() {
        return "#" + normalize(tertiaryColor);
    }

    public int primaryValue() {
        return colorValue(primaryColor);
    }

    public int secondaryValue() {
        return colorValue(secondaryColor);
    }

    public int tertiaryValue() {
        return colorValue(tertiaryColor);
    }

    private static String normalize(String s) {
        if (s == null) return "ffffff";
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 6 && t.matches("[0-9a-fA-F]{6}")) return t.toLowerCase();
        ChatFormatting cf = ChatFormatting.getByName(t);
        if (cf != null && cf.getColor() != null) return String.format("%06x", cf.getColor());
        return "ffffff";
    }

    private static int colorValue(String s) {
        return Integer.parseInt(normalize(s), 16);
    }
}
