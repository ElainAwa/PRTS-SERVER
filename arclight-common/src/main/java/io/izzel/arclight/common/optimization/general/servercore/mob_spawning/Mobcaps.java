/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;

// mobcap 判定工具（移植自 ServerCore Mobcaps）。
// 上游的 Moonrise/VMP 兼容分支在 NeoForge 服务端不适用，已去除。
public final class Mobcaps {
    public static final int MAGIC_NUMBER = (int) Math.pow(17.0D, 2.0D);

    private Mobcaps() {
    }

    public static boolean canSpawnForCategory(Level level, ChunkPos pos, MobCategory category, EnforcedMobcap config) {
        return !(level instanceof ServerLevel serverLevel) || canSpawnForCategory(serverLevel, pos, category, config);
    }

    public static boolean canSpawnForCategory(ServerLevel level, ChunkPos pos, MobCategory category, EnforcedMobcap config) {
        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null || category == MobCategory.MISC || !config.enforcesMobcap()) {
            return true;
        }

        final int capacity = category.getMaxInstancesPerChunk() + config.additionalCapacity();
        final int globalCapacity = toGlobalCapacity(state, capacity);

        final int globalCount = state.getMobCategoryCounts().getInt(category);
        if (globalCount >= globalCapacity) {
            return false;
        }

        LocalMobCapCalculator calculator = state.localMobCapCalculator;
        for (ServerPlayer player : calculator.getPlayersNear(pos)) {
            LocalMobCapCalculator.MobCounts mobCounts = calculator.playerMobCounts.get(player);
            if (mobCounts == null || mobCounts.counts.getOrDefault(category, 0) < capacity) {
                return true;
            }
        }

        return false;
    }

    private static int toGlobalCapacity(NaturalSpawner.SpawnState state, int capacity) {
        return capacity * state.getSpawnableChunkCount() / MAGIC_NUMBER;
    }
}
