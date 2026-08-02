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
