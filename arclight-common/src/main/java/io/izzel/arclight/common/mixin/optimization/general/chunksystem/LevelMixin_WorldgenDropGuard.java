/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.chunksystem;

import io.izzel.arclight.common.optimization.general.chunksystem.guards.ChunkSystemMainThreadGuard;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * worldgen 行为抑制（M2.2 四件套 ④）：chunk-system worker 上的
 * {@code Level.destroyBlock} 强制 drop=false。
 *
 * <p>生成期（features 等）方块破坏本无玩家因果，原版单线程生成下掉落物
 * 由主线程后续拾取处理；多线程化后在 worker 上生成掉落物实体属越界写
 * （实体容器非并发），强制不掉落并留一次日志定位来源（Bukkit 事件策略
 * 对齐：静默不发事件）。
 */
@Mixin(Level.class)
public abstract class LevelMixin_WorldgenDropGuard {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-ChunkSystem");
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    @ModifyVariable(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private boolean prts$suppressWorldgenDrops(boolean drop) {
        if (drop && ChunkSystemMainThreadGuard.isChunkSystemWorker()) {
            if (LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("[chunk-system] destroyBlock with drops on chunk-system worker; forcing drop=false");
            }
            return false;
        }
        return drop;
    }
}
