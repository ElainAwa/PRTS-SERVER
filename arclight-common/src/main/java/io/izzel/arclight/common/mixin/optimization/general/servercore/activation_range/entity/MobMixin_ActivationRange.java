/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import io.izzel.arclight.common.bridge.optimization.GoalSelectorBridge_ActivationRange;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Mob.class)
public abstract class MobMixin_ActivationRange extends LivingEntityMixin_ActivationRange {

    // @formatter:off
    @Shadow @Final public GoalSelector goalSelector;
    @Shadow @Final public GoalSelector targetSelector;
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();
        ((GoalSelectorBridge_ActivationRange) this.goalSelector).bridge$inactiveTick();
        ((GoalSelectorBridge_ActivationRange) this.targetSelector).bridge$inactiveTick();
    }
}
