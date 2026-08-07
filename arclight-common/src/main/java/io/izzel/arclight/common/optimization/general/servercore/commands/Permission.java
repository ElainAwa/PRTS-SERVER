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
