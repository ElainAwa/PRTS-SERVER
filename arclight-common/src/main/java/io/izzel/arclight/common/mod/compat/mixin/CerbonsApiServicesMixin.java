package io.izzel.arclight.common.mod.compat.mixin;

import io.izzel.arclight.common.mod.compat.ModIds;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ServiceLoader;

@LoadIfMod(modid = {ModIds.CERBONS_API}, condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "com.cerbon.cerbons_api.platform.Services", remap = false)
public class CerbonsApiServicesMixin {

    @SuppressWarnings("UnresolvedMixinReference")
    @Inject(method = "load", at = @At("HEAD"), cancellable = true)
    private static <T> void arclight$loadWithExplicitClassLoader(Class<T> clazz, CallbackInfoReturnable<T> cir) {
        ClassLoader loader = clazz.getClassLoader();
        if (loader == null) {
            return;
        }
        T service = ServiceLoader.load(clazz, loader).findFirst().orElse(null);
        if (service != null) {
            cir.setReturnValue(service);
        }
    }
}
