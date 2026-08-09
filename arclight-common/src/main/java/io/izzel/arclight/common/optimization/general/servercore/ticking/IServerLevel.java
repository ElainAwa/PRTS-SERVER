/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.ticking;

/** 每 tick 重置一次结冰/积雪计数器，替代逐区块 nextInt(16)（Airplane）。 */
public interface IServerLevel {

    void arclight$resetIceAndSnowTick();
}
