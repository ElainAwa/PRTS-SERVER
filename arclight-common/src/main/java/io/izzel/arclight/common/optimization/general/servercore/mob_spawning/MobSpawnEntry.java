package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import net.minecraft.world.entity.MobCategory;

// 单个 MobCategory 的生成参数（移植自 ServerCore MobSpawnEntry）。
public record MobSpawnEntry(MobCategory category, int capacity, int spawnInterval, int despawnDistance) {
}
