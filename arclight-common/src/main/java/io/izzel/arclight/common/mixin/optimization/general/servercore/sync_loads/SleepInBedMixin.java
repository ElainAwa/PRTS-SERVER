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
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.SleepInBed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(SleepInBed.class)
public class SleepInBedMixin {

    // Don't load chunks to find beds.
    @Inject(
            method = "checkExtraStartConditions",
            locals = LocalCapture.CAPTURE_FAILHARD,
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;",
                    ordinal = 0
            )
    )
    private void servercore$onlyProcessIfLoaded(ServerLevel level, LivingEntity owner, CallbackInfoReturnable<Boolean> cir, Brain<?> brain, GlobalPos globalPos) {
        if (!ChunkManager.hasChunk(level, globalPos.pos())) {
            cir.setReturnValue(false);
        }
    }
}
