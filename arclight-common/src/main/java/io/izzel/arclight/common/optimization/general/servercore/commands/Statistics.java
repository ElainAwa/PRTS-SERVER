/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.optimization.general.servercore.commands;

import io.izzel.arclight.common.mixin.optimization.general.servercore.commands.LevelBlockEntityTickersAccessor;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 性能统计工具（移植自 ServerCore Statistics）。
 * 偏差：改为无状态静态工具，区块数走 getLoadedChunksCount()，免开 ChunkMap.visibleChunkMap。
 */
public final class Statistics {

    private Statistics() {
    }

    public static List<Entity> allEntities(MinecraftServer server) {
        List<Entity> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                list.add(entity);
            }
        }
        return list;
    }

    public static List<TickingBlockEntity> allBlockEntities(MinecraftServer server) {
        List<TickingBlockEntity> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            list.addAll(((LevelBlockEntityTickersAccessor) level).arclight$blockEntityTickers());
        }
        return list;
    }

    public static List<Entity> entitiesNear(ServerPlayer player) {
        List<Entity> list = new ArrayList<>();
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (isNearby(player, entity.chunkPosition())) list.add(entity);
        }
        return list;
    }

    public static List<TickingBlockEntity> blockEntitiesNear(ServerPlayer player) {
        List<TickingBlockEntity> list = new ArrayList<>();
        for (TickingBlockEntity be : ((LevelBlockEntityTickersAccessor) player.level()).arclight$blockEntityTickers()) {
            BlockPos pos = be.getPos();
            if (pos != null && isNearby(player, new ChunkPos(pos))) list.add(be);
        }
        return list;
    }

    public static Map<String, Integer> entitiesByType(Iterable<Entity> entities) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Entity entity : entities) {
            map.merge(EntityType.getKey(entity.getType()).toString(), 1, Integer::sum);
        }
        return map;
    }

    public static Map<String, Integer> blockEntitiesByType(Iterable<TickingBlockEntity> blockEntities) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (TickingBlockEntity be : blockEntities) {
            map.merge(be.getType(), 1, Integer::sum);
        }
        return map;
    }

    public static Map<String, Integer> entitiesByPlayer(Iterable<ServerPlayer> players) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ServerPlayer player : players) {
            map.put(player.getScoreboardName(), entitiesNear(player).size());
        }
        return map;
    }

    public static Map<String, Integer> blockEntitiesByPlayer(Iterable<ServerPlayer> players) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (ServerPlayer player : players) {
            map.put(player.getScoreboardName(), blockEntitiesNear(player).size());
        }
        return map;
    }

    public static int chunkCount(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count += level.getChunkSource().getLoadedChunksCount();
        }
        return count;
    }

    private static boolean isNearby(Player player, ChunkPos pos) {
        return player.chunkPosition().getChessboardDistance(pos) <= DynamicSetting.VIEW_DISTANCE.get();
    }
}
