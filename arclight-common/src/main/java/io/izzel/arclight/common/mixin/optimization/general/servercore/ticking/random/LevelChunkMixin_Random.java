/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 *
 * This file adapts code from ServerCore by Wesley1808
 * (https://github.com/Wesley1808/ServerCore), licensed under GPL-3.0.
 * Original code Copyright (c) Wesley1808.
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore.ticking.random;

import io.izzel.arclight.common.optimization.general.servercore.ticking.ILevelChunk;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Airplane「Optimize-random-calls-in-chunk-ticking」：每区块自持雷击倒计时。
 * 偏差：上游用 Level.threadSafeRandom（1.20.1 为 private），此处改用 ThreadLocalRandom，分布等价且免开放字段。
 */
@Mixin(LevelChunk.class)
public class LevelChunkMixin_Random implements ILevelChunk {

    @Unique
    private int arclight$lightningTick = ThreadLocalRandom.current().nextInt(100000) << 1;

    @Override
    public final int arclight$shouldDoLightning(RandomSource randomSource, int thunderChance) {
        if (this.arclight$lightningTick-- <= 0) {
            this.arclight$lightningTick = randomSource.nextInt(thunderChance) << 1;
            return 0;
        }
        return -1;
    }
}
