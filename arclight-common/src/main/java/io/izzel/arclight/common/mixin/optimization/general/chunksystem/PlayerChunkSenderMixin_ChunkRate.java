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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 进服区块发送限速放宽：原版每批上限 64 块、初始 9 块/tick（客户端确认后
 * 才爬升），高负载服进服下载地形要等很多轮确认。上限提到 512。
 * 初始速率保持适度（32），避免首批发超量拉低客户端 ack 速率估算；
 * 并给目标速率加下限（32/tick）：ack 自锁在 ~12/s 时仍保持最低吞吐。
 * 附带发送遥测（每 200 tick 一行）。
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin_ChunkRate {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSend");

    /** 目标速率下限（块/tick）：防 ack 自锁把吞吐锁死在个位数。 */
    private static final float MIN_DESIRED_RATE = 32.0f;

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

    @ModifyConstant(method = "sendNextChunks", constant = @Constant(floatValue = 64.0f))
    private static float prts$maxBatchSize(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "handleChunkBatchReceived", constant = @Constant(floatValue = 64.0f))
    private static float prts$maxAckRate(float value) {
        return 512.0f;
    }

    @ModifyConstant(method = "<init>", constant = @Constant(floatValue = 9.0f))
    private static float prts$startRate(float value) {
        return START_RATE;
    }

    @Inject(method = "onChunkBatchReceivedByClient", at = @At("RETURN"))
    private void prts$minDesiredRate(float rate, CallbackInfo ci) {
        if (this.desiredChunksPerTick < MIN_DESIRED_RATE) {
            this.desiredChunksPerTick = MIN_DESIRED_RATE;
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
