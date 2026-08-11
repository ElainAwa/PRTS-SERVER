/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.neoforgeitems;

import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge InvWrapper 在 getInv() 为 null（容器状态异常/卸载中）时 getSlots
 * 直接 NPE，漏斗等提取路径会崩服——返回 0 槽位让提取逻辑安全跳过。
 */
@Mixin(value = InvWrapper.class, remap = false)
public abstract class InvWrapperMixin_NoNull {

    @Shadow(remap = false)
    abstract net.minecraft.world.Container getInv();

    @Inject(method = "getSlots", at = @At("HEAD"), cancellable = true, remap = false)
    private void arclight$guardNullInv(CallbackInfoReturnable<Integer> cir) {
        if (this.getInv() == null) {
            cir.setReturnValue(0);
        }
    }
}
