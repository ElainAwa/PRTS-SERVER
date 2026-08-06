/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.features.merging;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ExperienceOrb.class)
public class ExperienceOrbMixin {

    @ModifyConstant(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z", constant = @Constant(intValue = 40), require = 0)
    private static int servercore$modifyMergeChance(int constant) {
        return ServerCoreConfig.features().enabled() ? ServerCoreConfig.features().xpMergeChance() : constant;
    }

    @ModifyArg(
            method = "scanForEntities",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"
            )
    )
    private double servercore$modifyMergeRadius(double value) {
        return ServerCoreConfig.features().enabled() ? ServerCoreConfig.features().xpMergeRadius() : value;
    }
}
