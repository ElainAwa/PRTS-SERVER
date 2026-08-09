/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.activation_range.fixes;

import io.izzel.arclight.common.bridge.optimization.EntityBridge_ActivationRange;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.animal.Ocelot;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 非激活豹猫的 tickCount 不再增长，改用完整 tick 计数判断自然消失。
@Mixin(Ocelot.class)
public class OcelotMixin_ActivationRange {

    @Redirect(method = "removeWhenFarAway", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/world/entity/animal/Ocelot;tickCount:I"))
    private int activationRange$fixOcelotDespawning(Ocelot ocelot) {
        if (!ServerCoreConfig.isActivationRangeEnabled()) return ocelot.tickCount;
        return ((EntityBridge_ActivationRange) ocelot).bridge$getFullTickCount();
    }
}
