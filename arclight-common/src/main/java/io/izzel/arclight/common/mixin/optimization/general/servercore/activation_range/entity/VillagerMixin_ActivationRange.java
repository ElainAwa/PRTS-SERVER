/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Villager.class)
public abstract class VillagerMixin_ActivationRange extends AgeableMobMixin_ActivationRange {

    // @formatter:off
    @Shadow private void maybeDecayGossip() { throw new AbstractMethodError(); }
    // @formatter:on

    // 非激活村民仍衰减不满值与流言，避免声望长期冻结
    // get/setUnhappyCounter 定义在 AbstractVillager，@Shadow 不跨超类，改强转调用
    @Override
    public void inactiveTick() {
        final Villager self = (Villager) (Object) this;
        if (self.getUnhappyCounter() > 0) {
            self.setUnhappyCounter(self.getUnhappyCounter() - 1);
        }
        this.maybeDecayGossip();
        super.inactiveTick();
    }
}
