/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.commands;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 LocalMobCapCalculator$MobCounts.counts；该内部类在 1.20.1 为包私有，只能用 targets 指定。
 */
@Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
public interface MobCountsAccessor {

    @Accessor("counts")
    Object2IntMap<MobCategory> arclight$counts();
}
