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
