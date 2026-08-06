/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.ticking;

import net.minecraft.util.RandomSource;

/** 每区块自持雷击倒计时，替代每 tick 的 nextInt 调用（Airplane）。 */
public interface ILevelChunk {

    int arclight$shouldDoLightning(RandomSource randomSource, int thunderChance);
}
