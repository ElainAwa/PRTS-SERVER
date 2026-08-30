/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.longs.LongSet;

/** 放宽进服区块发送：批上限 512、初始 32、速率下限由配置控制。 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin_ChunkRate {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSend");

    /** 初始速率（块/tick）：适度，避免首批发超量。 */
    private static final float START_RATE = 32.0f;

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private float batchQuota;

    @Shadow
    private int unacknowledgedBatches;

    @Shadow
    private int maxUnacknowledgedBatches;

    @Unique
    private long prts$lastSendLogTick = -1000;

    @Redirect(method = "sendNextChunks",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;MAX_CHUNKS_PER_TICK:F"))
    private static float prts$maxBatchSize(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "onChunkBatchReceivedByClient", constant = @Constant(floatValue = 64.0f))
    private static float prts$maxAckRate(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(floatValue = 9.0f))
    private static float prts$startRate(float value) {
        return START_RATE;
    }

    @Inject(method = "onChunkBatchReceivedByClient", at = @At("RETURN"))
    private void prts$minDesiredRate(float rate, CallbackInfo ci) {
        float floor = io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig.chunkSendRateFloor;
        if (floor > 0f && this.desiredChunksPerTick < floor) {
            this.desiredChunksPerTick = floor;
        }
    }

    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void prts$sendTelemetry(ServerPlayer player, CallbackInfo ci) {
        long tick = player.serverLevel().getGameTime();
        if (tick - this.prts$lastSendLogTick < 200) {
            return;
        }
        this.prts$lastSendLogTick = tick;
        LOGGER.info("[chunk-send] player={} pending={} unacked={}/{} desired={} quota={}",
                player.getGameProfile().getName(), this.pendingChunks.size(),
                this.unacknowledgedBatches, this.maxUnacknowledgedBatches,
                this.desiredChunksPerTick, this.batchQuota);
    }
}
