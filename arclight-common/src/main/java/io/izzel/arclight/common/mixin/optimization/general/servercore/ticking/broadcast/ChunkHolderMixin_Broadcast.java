/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ticking.broadcast;

import io.izzel.arclight.common.optimization.general.servercore.ServerCoreConfig;
import io.izzel.arclight.common.optimization.general.servercore.ticking.IServerChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkHolder.class)
public class ChunkHolderMixin_Broadcast {

    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;

    @Inject(
            method = "blockChanged",
            require = 0,
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/server/level/ChunkHolder;hasChangedSections:Z",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void arclight$onBlockChanged(BlockPos blockPos, CallbackInfo ci) {
        this.arclight$requiresBroadcast();
    }

    @Inject(
            method = "sectionLightChanged",
            require = 0,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/BitSet;set(I)V"
            )
    )
    private void arclight$onLightChanged(LightLayer lightLayer, int i, CallbackInfo ci) {
        this.arclight$requiresBroadcast();
    }

    @Unique
    private void arclight$requiresBroadcast() {
        if (!ServerCoreConfig.optimizations().optimizeChunkBroadcasts()) return;
        if (this.levelHeightAccessor instanceof ServerLevel serverLevel) {
            ((IServerChunkCache) serverLevel.getChunkSource()).arclight$requiresBroadcast((ChunkHolder) (Object) this);
        }
    }
}
