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
