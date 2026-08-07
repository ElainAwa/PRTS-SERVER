package io.izzel.arclight.common.optimization.general.servercore.ticking;

import net.minecraft.util.RandomSource;

/** 每区块自持雷击倒计时，替代每 tick 的 nextInt 调用（Airplane）。 */
public interface ILevelChunk {

    int arclight$shouldDoLightning(RandomSource randomSource, int thunderChance);
}
