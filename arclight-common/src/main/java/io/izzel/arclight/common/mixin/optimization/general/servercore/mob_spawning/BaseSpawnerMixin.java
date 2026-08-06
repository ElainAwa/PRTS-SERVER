/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import com.llamalad7.mixinextras.sugar.Local;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.mob_spawning.Mobcaps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
    @Shadow
    protected abstract void delay(Level level, BlockPos blockPos);

    @Inject(
            method = "serverTick",
            cancellable = true,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
                    ordinal = 0
            )
    )
    private void servercore$enforceMobcap(ServerLevel level, BlockPos pos, CallbackInfo ci, @Local(ordinal = 0) Entity entity) {
        boolean canSpawn = Mobcaps.canSpawnForCategory(
                level,
                entity.chunkPosition(),
                entity.getType().getCategory(),
                ServerCoreConfig.mobSpawning().monsterSpawner()
        );

        if (!canSpawn) {
            this.delay(level, pos);
            ci.cancel();
        }
    }
}
