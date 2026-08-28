/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 进服区块发送限速放宽：原版每批上限 64 块、初始 9 块/tick（客户端确认后
 * 才爬升），高负载服进服下载地形要等很多轮确认。上限提到 512、初始 128，
 * 发送速率不再成为首登瓶颈（加载完成率仍是主约束）。
 */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderMixin_ChunkRate {

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
        return 128.0f;
    }
}
