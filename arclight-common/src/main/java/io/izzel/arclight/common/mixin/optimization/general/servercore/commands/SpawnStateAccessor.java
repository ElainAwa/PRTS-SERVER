/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 NaturalSpawner.SpawnState.localMobCapCalculator（1.20.1 下为 private final）。
 */
@Mixin(NaturalSpawner.SpawnState.class)
public interface SpawnStateAccessor {

    @Accessor("localMobCapCalculator")
    LocalMobCapCalculator arclight$localMobCapCalculator();
}
