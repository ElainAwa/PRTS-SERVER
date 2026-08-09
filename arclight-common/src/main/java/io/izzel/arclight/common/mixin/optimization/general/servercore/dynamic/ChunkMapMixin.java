/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dynamic;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 用动态 CHUNK_TICK_DISTANCE 限制 chunk tick 距离（移植自 ServerCore ChunkMapMixin）。
 */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @ModifyReturnValue(method = "playerIsCloseEnoughForSpawning", at = @At(value = "RETURN"))
    private boolean prts$withinChunkTickDistance(boolean original, ServerPlayer player, ChunkPos pos) {
        // 关闭 dynamic 时完全回退原版判定
        if (!original || !ServerCoreConfig.dynamicActive()) return original;
        return player.chunkPosition().getChessboardDistance(pos) <= DynamicSetting.CHUNK_TICK_DISTANCE.get();
    }
}
