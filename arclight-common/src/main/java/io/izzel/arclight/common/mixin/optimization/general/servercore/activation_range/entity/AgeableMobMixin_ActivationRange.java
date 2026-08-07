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
