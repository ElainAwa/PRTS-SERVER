/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 暴露 LocalMobCapCalculator.playerMobCounts（private final）。
 * 值类型 MobCounts 为包私有，此处以 Object 承接（擦除后同为 Ljava/util/Map;）。
 */
@Mixin(LocalMobCapCalculator.class)
public interface LocalMobCapCalculatorAccessor {

    @Accessor("playerMobCounts")
    Map<ServerPlayer, Object> arclight$playerMobCounts();
}
