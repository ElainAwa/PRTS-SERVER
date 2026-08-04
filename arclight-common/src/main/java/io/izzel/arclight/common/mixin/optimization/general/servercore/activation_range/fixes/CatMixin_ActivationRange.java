package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.fixes;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_FullActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.animal.Cat;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 非激活猫的 tickCount 不再增长，改用完整 tick 计数判断自然消失。
@Mixin(Cat.class)
public class CatMixin_ActivationRange {

    @Redirect(method = "removeWhenFarAway", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/world/entity/animal/Cat;tickCount:I"))
    private int activationRange$fixCatDespawning(Cat cat) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return cat.tickCount;
        return ((EntityBridge_FullActivationRange) cat).bridge$getFullTickCount();
    }
}
