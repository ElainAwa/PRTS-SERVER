/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/** Utility methods for getting chunks without triggering synchronous chunk loads. */
public class ChunkManager {

    public static Holder<Biome> getRoughBiome(Level level, BlockPos pos) {
        ChunkAccess chunk = getChunkNow(level, pos);
        int x = pos.getX() >> 2;
        int y = pos.getY() >> 2;
        int z = pos.getZ() >> 2;

        return chunk != null ? chunk.getNoiseBiome(x, y, z) : level.getUncachedNoiseBiome(x, y, z);
    }

    public static BlockState getBlockState(Level level, BlockPos pos) {
        ChunkAccess chunk = getChunkNow(level, pos);
        return chunk != null ? chunk.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    public static ChunkAccess getChunkNow(LevelReader levelReader, BlockPos pos) {
        return getChunkNow(levelReader, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static ChunkAccess getChunkNow(LevelReader levelReader, int chunkX, int chunkZ) {
        if (levelReader instanceof ServerLevel level) {
            // getChunk(..., false) returns the currently visible chunk or null WITHOUT loading it.
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
            return serverLevel.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
        } else {
            return true;
        }
    }

    // (已删除 2026-08-02) disableSpawnChunks 死代码：无调用方；实际由
    // servercore.features.spawn_chunks.ServerLevelMixin(cancel addRegionTicket) + MinecraftTweaks 接管

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
