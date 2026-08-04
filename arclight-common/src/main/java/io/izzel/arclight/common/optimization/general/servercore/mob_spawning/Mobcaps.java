package io.izzel.arclight.common.optimization.general.servercore.mob_spawning;

import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.LocalMobCapCalculatorAccessor;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.LocalMobCapCalculatorInvoker;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.MobCountsAccessor;
import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.SpawnStateAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;

import java.util.List;
import java.util.Map;

// mobcap 判定工具（移植自 ServerCore Mobcaps，1.20.1 适配：accessor 强转访问私有字段）。
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

        LocalMobCapCalculator calculator = ((SpawnStateAccessor) (Object) state).arclight$localMobCapCalculator();
        Map<ServerPlayer, Object> countsMap = ((LocalMobCapCalculatorAccessor) (Object) calculator).arclight$playerMobCounts();
        List<ServerPlayer> playersNear = ((LocalMobCapCalculatorInvoker) (Object) calculator).arclight$getPlayersNear(pos);
        for (ServerPlayer player : playersNear) {
            Object mobCounts = countsMap.get(player);
            if (mobCounts == null || ((MobCountsAccessor) (Object) mobCounts).arclight$counts().getOrDefault(category, 0) < capacity) {
                return true;
            }
        }

        return false;
    }

    private static int toGlobalCapacity(NaturalSpawner.SpawnState state, int capacity) {
        return capacity * state.getSpawnableChunkCount() / MAGIC_NUMBER;
    }
}
