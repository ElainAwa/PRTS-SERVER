package io.izzel.arclight.common.mixin.optimization.general.minecrafttweaks;

import io.izzel.arclight.common.optimization.general.minecrafttweaks.MinecraftTweaks;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * HolderSet.contains 空守卫（源自 Mohist 1.20.1 HolderSet patch，去 Mohist 化）。
 * 入参 Holder 为 null 时直接返回 false，避免后续 p_205834_.m_203656_(this.f_205829_) 触发 NPE。
 */
@Mixin({HolderSet.Direct.class, HolderSet.ListBacked.class, HolderSet.Named.class})
public abstract class MixinHolderSet_NullGuard {

    @Inject(method = "contains(Lnet/minecraft/core/Holder;)Z", at = @At("HEAD"), cancellable = true)
    private void luminara$nullGuard(Holder<?> holder, CallbackInfoReturnable<Boolean> cir) {
        if (MinecraftTweaks.holdersetNullGuardEnabled() && holder == null) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }
}
