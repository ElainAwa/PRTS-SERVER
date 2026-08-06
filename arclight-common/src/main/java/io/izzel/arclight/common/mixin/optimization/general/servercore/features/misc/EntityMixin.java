/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.features.misc;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public class EntityMixin {

    // Fall back to default spawn position if the spawn chunks aren't loaded.
    @Redirect(
            method = "findDimensionEntryPoint",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"
            )
    )
    private BlockPos servercore$fixSpawnHeight(ServerLevel level, Heightmap.Types types, BlockPos blockPos) {
        if (!ServerCoreConfig.features().enabled()) {
            return level.getHeightmapPos(types, blockPos);
        }
        return ChunkManager.hasChunk(level, blockPos) ? level.getHeightmapPos(types, blockPos) : blockPos;
    }
}
