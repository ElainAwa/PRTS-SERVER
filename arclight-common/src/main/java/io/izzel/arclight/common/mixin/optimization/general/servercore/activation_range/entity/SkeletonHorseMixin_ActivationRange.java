package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.entity;

import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SkeletonHorse.class)
public abstract class SkeletonHorseMixin_ActivationRange extends AgeableMobMixin_ActivationRange {

    // @formatter:off
    @Shadow @Final private static int TRAP_MAX_LIFE;
    @Shadow private boolean isTrap;
    @Shadow public int trapTime;
    // @formatter:on

    // 骷髅马陷阱到期照常消失，避免非激活时永久滞留
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        if (this.isTrap && this.trapTime++ >= TRAP_MAX_LIFE) {
            this.discard();
        }
    }
}
