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
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RemoveBlockGoal.class)
public class RemoveBlockGoalMixin {

    @Redirect(method = "isValidTarget", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess servercore$onlyValidateIfLoaded(LevelReader level, int x, int z, ChunkStatus status, boolean create) {
        return ChunkManager.getChunkNow(level, x, z);
    }
}
