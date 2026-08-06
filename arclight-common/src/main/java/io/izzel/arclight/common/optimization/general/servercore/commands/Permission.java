/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.commands;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

/**
 * 命令权限（移植自 ServerCore Permission）。
 * 本核心无 Forge PermissionAPI，仅按 op 等级校验。
 */
public class Permission {

    public static boolean check(CommandSourceStack source, String node, int level) {
        return source.hasPermission(level);
    }

    public static Predicate<CommandSourceStack> require(String node, int level) {
        return source -> check(source, node, level);
    }
}
