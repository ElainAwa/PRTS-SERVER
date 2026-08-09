/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import net.minecraft.world.entity.MobCategory;

// 单个 MobCategory 的生成参数（移植自 ServerCore MobSpawnEntry）。
public record MobSpawnEntry(MobCategory category, int capacity, int spawnInterval, int despawnDistance) {
}
