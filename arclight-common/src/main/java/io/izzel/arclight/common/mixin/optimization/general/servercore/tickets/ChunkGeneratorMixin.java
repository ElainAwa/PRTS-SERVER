/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.tickets;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig.Feature;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

// Ported from Wesley1808/ServerCore (Mojmap / 1.21.1).
@Mixin(ChunkGenerator.class)
public class ChunkGeneratorMixin {

    @Redirect(
            method = "getMobsAt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/StructureManager;getAllStructuresAt(Lnet/minecraft/core/BlockPos;)Ljava/util/Map;"
            )
    )
    private Map<Structure, LongSet> servercore$preventAddingTickets(StructureManager structureManager, BlockPos pos) {
        if (!ServerCoreConfig.isEnabled(Feature.CHUNK_TICKETS)) return structureManager.getAllStructuresAt(pos);
        ChunkAccess chunk = ChunkManager.getChunkNow(structureManager.level, pos);
        return chunk != null ? chunk.getAllReferences() : structureManager.getAllStructuresAt(pos);
    }
}
