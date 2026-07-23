package io.izzel.arclight.common.mixin.optimization.general.servercore.tickets;

import io.izzel.arclight.common.optimization.general.servercore.ChunkManager;
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
        ChunkAccess chunk = ChunkManager.getChunkNow(structureManager.level, pos);
        return chunk != null ? chunk.getAllReferences() : structureManager.getAllStructuresAt(pos);
    }
}
