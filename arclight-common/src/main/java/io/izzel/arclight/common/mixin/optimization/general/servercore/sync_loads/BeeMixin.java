/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.sync_loads;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig.Feature;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(Bee.class)
public abstract class BeeMixin extends Animal {
    @Shadow
    @Nullable
    BlockPos hivePos;

    private BeeMixin(EntityType<? extends Animal> entityType, Level level) {
        super(entityType, level);
    }

    // Entity.level is a private field in 1.21.1 Mojmap and cannot be @Shadow'd from a
    @Inject(method = "isHiveValid", at = @At("HEAD"), cancellable = true)
    private void servercore$onlyValidateIfLoaded(CallbackInfoReturnable<Boolean> cir) {
        if (!ServerCoreConfig.isEnabled(Feature.SYNC_LOADS)) return;
        if (!ChunkManager.hasChunk(this.level(), this.hivePos)) {
            cir.setReturnValue(false);
        }
    }
}
