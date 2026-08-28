/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.mob_spawning;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 自然刷怪关闭时跳过 createState 的实体遍历：mobcap 100% 的 5000 实体场景下
 * 每 tick 遍历全部实体算 mobcap 是纯主线程开销；spawnForChunk 已全拒，
 * 空实体列表 → mobcap 全零 → 生成依旧全拒，语义等价。
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin_SpawnState {

    @ModifyArg(method = "tickChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/NaturalSpawner$LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"),
            index = 1)
    private Iterable<Entity> arclight$skipSpawnStateIteration(Iterable<Entity> entities) {
        if (!ServerCoreConfig.mobSpawningActive()) {
            return java.util.List.of();
        }
        return entities;
    }
}
