/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Bee.isTooFarAway (private under Mojmap) for BeeMixin.
 * Equivalent to ServerCore's original @ModifyExpressionValue behaviour.
 */
@Mixin(Bee.class)
public interface BeeAccessor {
    @Invoker("isTooFarAway")
    boolean arclight$isTooFarAway(BlockPos pos);
}
