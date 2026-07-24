package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility methods for getting chunks (ServerCore port, Mojmap / NeoForge 1.21.1).
 *
 * <p>Adapted from Wesley1808/ServerCore. The Moonrise-specific branch (Environment.MOD_MOONRISE)
 * is dropped: on this platform we always use the vanilla "get visible chunk if loaded, do not load"
 * path via {@link ServerChunkCache#getChunk(int, int, ChunkStatus, boolean)}, which avoids any
 * reference to the (private) {@code ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE} field.</p>
 */
public class ChunkManager {

    @NotNull
    public static Holder<Biome> getRoughBiome(Level level, BlockPos pos) {
        ChunkAccess chunk = getChunkNow(level, pos);
        int x = pos.getX() >> 2;
        int y = pos.getY() >> 2;
        int z = pos.getZ() >> 2;

        return chunk != null ? chunk.getNoiseBiome(x, y, z) : level.getUncachedNoiseBiome(x, y, z);
    }

    @NotNull
    public static BlockState getBlockState(Level level, BlockPos pos) {
        ChunkAccess chunk = getChunkNow(level, pos);
        return chunk != null ? chunk.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Nullable
    public static ChunkAccess getChunkNow(LevelReader levelReader, BlockPos pos) {
        return getChunkNow(levelReader, pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Nullable
    public static ChunkAccess getChunkNow(LevelReader levelReader, int chunkX, int chunkZ) {
        if (levelReader instanceof ServerLevel level) {
            return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        } else {
            return levelReader.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        }
    }

    public static boolean hasChunk(Level level, BlockPos pos) {
        return hasChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean hasChunk(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getChunkSource().hasChunk(chunkX, chunkZ);
        }
        return true;
    }

    // Utility method from PaperMC (MC-Utils.patch)
    public static boolean areChunksLoadedForMove(ServerLevel level, AABB box) {
        int minBlockX = Mth.floor(box.minX - 1.0E-7D) - 3;
        int maxBlockX = Mth.floor(box.maxX + 1.0E-7D) + 3;

        int minBlockZ = Mth.floor(box.minZ - 1.0E-7D) - 3;
        int maxBlockZ = Mth.floor(box.maxZ + 1.0E-7D) + 3;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;

        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                if (!hasChunk(level, chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        return true;
    }
}
