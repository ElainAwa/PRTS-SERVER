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
import net.minecraft.world.entity.projectile.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// ServerCore 分别处理 Arrow 与 SpectralArrow，这里合并到公共父类 AbstractArrow。
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow protected boolean inGround;
    @Shadow protected abstract void tickDespawn();
    // @formatter:on

    // 插在地上的箭照常计时消失
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        if (this.inGround) {
            this.tickDespawn();
        }
    }
}
