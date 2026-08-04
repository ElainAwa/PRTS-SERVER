package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.EntityMixin_ActivationRange;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// Ported from Wesley1808/ServerCore。1.20.1 无 ItemEntityBridge（1.21.1 的 Forge 销毁桥为空实现），直接删调用。
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin_ActivationRange extends EntityMixin_ActivationRange {

    // @formatter:off
    @Shadow @Final private static int INFINITE_PICKUP_DELAY;
    @Shadow @Final private static int INFINITE_LIFETIME;
    @Shadow public int pickupDelay;
    @Shadow public int age;
    // @formatter:on

    @Override
    public void inactiveTick() {
        super.inactiveTick();

        if (this.pickupDelay > 0 && this.pickupDelay != INFINITE_PICKUP_DELAY) {
            this.pickupDelay--;
        }

        if (this.age != INFINITE_LIFETIME) {
            this.age++;
        }
    }
}
