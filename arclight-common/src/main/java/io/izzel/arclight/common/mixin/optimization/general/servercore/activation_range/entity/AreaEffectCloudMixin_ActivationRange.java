/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.EntityMixin_ActivationRange;
import net.minecraft.world.entity.AreaEffectCloud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow private int waitTime;
    @Shadow private int duration;
    // @formatter:on

    // 药水云到期照常消失
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        if (++this.tickCount >= this.waitTime + this.duration) {
            this.discard();
        }
    }
}
