/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.breeding_cap;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.breeding_cap.BreedingCapConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Allay.class)
public abstract class AllayMixin extends PathfinderMob {
    private AllayMixin(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    // 1.20.1 中 resetDuplicationCooldown 为 public（1.21.1 为 protected）
    @Shadow
    public abstract void resetDuplicationCooldown();

    @Inject(method = "duplicateAllay", at = @At("HEAD"), cancellable = true)
    private void servercore$enforceBreedCap(CallbackInfo ci) {
        BreedingCapConfig config = ServerCoreConfig.breedingCap();
        if (config.enabled() && config.animals().exceedsLimit(this)) {
            this.resetDuplicationCooldown();
            ci.cancel();
        }
    }
}
