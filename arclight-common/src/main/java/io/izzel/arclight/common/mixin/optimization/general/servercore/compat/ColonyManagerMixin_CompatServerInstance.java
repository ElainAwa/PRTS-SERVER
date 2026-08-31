/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.compat;

import com.minecolonies.api.compatibility.ICompatibilityManager;
import com.minecolonies.core.colony.ColonyManager;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecolonies returns the undiscovered client compatibility manager whenever
 * the calling thread name does not contain "server". Region workers therefore
 * always got an empty ore/compost table, which broke miner checks and spammed
 * "when empty" errors. On the dedicated server every caller must get the
 * discovered server instance.
 */
@LoadIfMod(modid = "minecolonies", condition = LoadIfMod.ModCondition.PRESENT)
@Mixin(value = ColonyManager.class, remap = false)
public abstract class ColonyManagerMixin_CompatServerInstance {

    @Shadow
    @Final
    private ICompatibilityManager compatibilityManager;

    @Inject(method = "getCompatibilityManager", at = @At("HEAD"), cancellable = true)
    private void arclight$returnServerInstance(CallbackInfoReturnable<ICompatibilityManager> cir) {
        cir.setReturnValue(this.compatibilityManager);
    }
}
