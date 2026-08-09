/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.world.entity.animal.frog.Tadpole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Tadpole.class)
public abstract class TadpoleMixin_ActivationRange extends MobMixin_ActivationRange {

    // @formatter:off
    @Shadow private int age;
    @Shadow private void setAge(int age) { throw new AbstractMethodError(); }
    // @formatter:on

    // 蝌蚪照常长大
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        this.setAge(this.age + 1);
    }
}
