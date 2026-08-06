/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.dynamic;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.dynamic.DynamicSetting;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 用动态 CHUNK_TICK_DISTANCE 限制 chunk tick 距离（移植自 ServerCore ChunkMapMixin）。
 * 1.20.1 树无 mixin-extras，故将 @ModifyReturnValue 译为 @Inject(at=RETURN, cancellable) + cir.setReturnValue。
 */
@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Inject(method = "playerIsCloseEnoughForSpawning", at = @At("RETURN"), cancellable = true)
    private void prts$withinChunkTickDistance(ServerPlayer player, ChunkPos pos, CallbackInfoReturnable<Boolean> cir) {
        boolean original = cir.getReturnValue();
        // 关闭 dynamic 时完全回退原版判定
        if (!original || !ServerCoreConfig.dynamicActive()) return;
        cir.setReturnValue(player.chunkPosition().getChessboardDistance(pos) <= DynamicSetting.CHUNK_TICK_DISTANCE.get());
    }
}
