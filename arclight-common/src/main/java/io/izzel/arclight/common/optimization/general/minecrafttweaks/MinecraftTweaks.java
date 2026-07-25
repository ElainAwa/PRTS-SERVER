package io.izzel.arclight.common.optimization.general.minecrafttweaks;

import io.izzel.arclight.i18n.ArclightConfig;

/**
 * 源自 Mohist 1.20.1 下游移植的总开关读取（去 Mohist 化）。
 * <p>
 * 两条"优化"（村民脑切、出生点区块）受实验性总开关罩着；
 * 两条 NPE 守卫为安全修复，独立常开（除非显式关闭）。
 */
public final class MinecraftTweaks {

    public static boolean villagerBrainOffloadEnabled() {
        var opt = ArclightConfig.spec().getOptimization();
        return opt.isExperimentalOptimizationsEnabled() && opt.getMinecraftOptimizations().isVillagerBrainOffloadEnabled();
    }

    public static boolean disableSpawnChunksEnabled() {
        var opt = ArclightConfig.spec().getOptimization();
        return opt.isExperimentalOptimizationsEnabled() && opt.getMinecraftOptimizations().isDisableSpawnChunksEnabled();
    }

    public static boolean spawnerNullGuardEnabled() {
        return ArclightConfig.spec().getOptimization().getMinecraftOptimizations().isSpawnerNullGuardEnabled();
    }

    public static boolean holdersetNullGuardEnabled() {
        return ArclightConfig.spec().getOptimization().getMinecraftOptimizations().isHoldersetNullGuardEnabled();
    }
}
