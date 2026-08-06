/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.IMobCategory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = NaturalSpawner.class, priority = 900)
public class NaturalSpawnerMixin {

    // 关闭原版的"持久类整批生成"节奏，改由每类别 spawn-interval 控制。
    @ModifyVariable(method = "spawnForChunk", at = @At("HEAD"), index = 5, argsOnly = true)
    private static boolean servercore$neverSpawnPersistent(boolean shouldSpawnPersistent) {
        return !ServerCoreConfig.mobSpawningActive() && shouldSpawnPersistent;
    }

    @ModifyExpressionValue(
            method = "spawnForChunk",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/MobCategory;isPersistent()Z"
            )
    )
    private static boolean servercore$shouldCancelSpawn(boolean isPersistent, ServerLevel level, @Local(ordinal = 0) MobCategory category) {
        if (!ServerCoreConfig.mobSpawningActive()) {
            return isPersistent;
        }

        if (category.getMaxInstancesPerChunk() <= 0) {
            return true;
        }

        final int interval = IMobCategory.getSpawnInterval(category);
        return interval > 1 && level.getGameTime() % interval != 0;
    }
}
