/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import javax.annotation.Nullable;

@Mixin(Bee.class)
public abstract class BeeMixin_ActivationRange extends AgeableMobMixin_ActivationRange {

    // @formatter:off
    @Shadow @Nullable BlockPos hivePos;
    @Shadow abstract boolean isHiveValid();
    // @formatter:on

    // 蜂巢失效时清引用，避免非激活的蜜蜂长期持有已拆除的蜂巢
    @Override
    public void inactiveTick() {
        if (this.bridge$getFullTickCount() % 20 == 0 && !this.isHiveValid()) {
            this.hivePos = null;
        }
        super.inactiveTick();
    }
}
