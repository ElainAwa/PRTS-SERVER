/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range;

import io.izzel.arclight.common.bridge.optimization.GoalSelectorBridge_ActivationRange;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// Ported from Wesley1808/ServerCore;原始实现 Paper Entity-Activation-Range-2.0 (Aikar, GPL-3.0)。
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin_ActivationRange implements GoalSelectorBridge_ActivationRange {

    @Shadow public abstract void tick();

    @Unique
    private int activationRange$curRate;

    // 非激活生物的 AI 每秒只跑一次
    @Override
    public void bridge$inactiveTick() {
        if (++this.activationRange$curRate % 20 == 0) {
            this.tick();
        }
    }
}
