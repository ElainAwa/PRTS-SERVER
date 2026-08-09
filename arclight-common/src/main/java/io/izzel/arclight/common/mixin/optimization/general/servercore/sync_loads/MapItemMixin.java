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
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(MapItem.class)
public class MapItemMixin {

    // Stop maps from loading chunks.
    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
            )
    )
    private LevelChunk servercore$onlyUpdateIfLoaded(Level level, int chunkX, int chunkZ) {
        if (!ServerCoreConfig.isEnabled(Feature.SYNC_LOADS)) return level.getChunk(chunkX, chunkZ);
        return (LevelChunk) ChunkManager.getChunkNow(level, chunkX, chunkZ);
    }

    @Redirect(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunk;isEmpty()Z"
            )
    )
    private boolean servercore$validateNotNull(LevelChunk chunk) {
        if (!ServerCoreConfig.isEnabled(Feature.SYNC_LOADS)) return chunk.isEmpty();
        return chunk == null || chunk.isEmpty();
    }
}
