/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.world.entity.AgeableMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(AgeableMob.class)
public abstract class AgeableMobMixin_ActivationRange extends MobMixin_ActivationRange {

    // @formatter:off
    @Shadow protected int age;
    @Shadow public abstract int getAge();
    @Shadow public abstract void setAge(int age);
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();
        final int age = this.getAge();
        if (age < 0) {
            this.setAge(age + 1);
        } else if (age > 0) {
            this.setAge(age - 1);
        }
    }
}
