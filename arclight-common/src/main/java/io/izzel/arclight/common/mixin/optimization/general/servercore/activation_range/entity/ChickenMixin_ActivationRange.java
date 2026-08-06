/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Chicken.class)
public abstract class ChickenMixin_ActivationRange extends AgeableMobMixin_ActivationRange {

    // @formatter:off
    @Shadow public int eggTime;
    @Shadow public boolean isChickenJockey;
    // @formatter:on

    // 非激活的鸡照常下蛋，保证刷蛋机不受影响
    // playSound/spawnAtLocation/gameEvent/getRandom 定义在 Entity，@Shadow 不跨超类，改强转调用
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        if (!this.isChickenJockey && this.age >= 0 && this.isAlive() && --this.eggTime <= 0) {
            final Chicken self = (Chicken) (Object) this;
            self.playSound(SoundEvents.CHICKEN_EGG, 1.0F,
                    (self.getRandom().nextFloat() - self.getRandom().nextFloat()) * 0.2F + 1.0F);
            self.spawnAtLocation(Items.EGG);
            self.gameEvent(GameEvent.ENTITY_PLACE);
            this.eggTime = self.getRandom().nextInt(6000) + 6000;
        }
    }
}
