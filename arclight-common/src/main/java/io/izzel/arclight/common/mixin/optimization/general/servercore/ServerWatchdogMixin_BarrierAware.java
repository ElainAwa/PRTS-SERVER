/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.mixin.optimization.general.servercore;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.general.servercore.DimensionTickManager;
import net.minecraft.Util;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让原版 watchdog 感知并行 barrier：主线程在维度并行等待期间，watchdog
 * 不再因 tick 时间停滞而误杀服务器（同时重置其时间基准，避免出 barrier 后误杀）。
 * 配置见 prts-features.yml（barrier-watchdog-aware，默认开；false = 原版行为）。
 */
@Mixin(ServerWatchdog.class)
public abstract class ServerWatchdogMixin_BarrierAware {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Barrier");

    private static long prts$lastWarnNanos = 0L;

    /** 卡死诊断：barrier 等待超 15s 且距上次 dump 超 30s 时打全线程栈。 */
    private static long prts$lastDumpNanos = 0L;

    @Redirect(method = "run", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/dedicated/DedicatedServer;getNextTickTime()J"))
    private long arclight$watchdogNextTickTime(DedicatedServer server) {
        if (PRTSFeaturesConfig.barrierWatchdogAware && DimensionTickManager.inDimensionTick()) {
            long now = Util.getNanos();
            if (now - prts$lastWarnNanos > 60_000_000_000L) {
                prts$lastWarnNanos = now;
                long stalled = now - server.getNextTickTime();
                LOGGER.warn("[PRTS-Barrier] main thread waiting in parallel barrier for {}ms; watchdog suppressed",
                        stalled / 1_000_000L);
                if (stalled > 15_000_000_000L && now - prts$lastDumpNanos > 30_000_000_000L) {
                    prts$lastDumpNanos = now;
                    DimensionTickManager.barrierTimeoutDump("watchdog-stall");
                }
            }
            return now;
        }
        return server.getNextTickTime();
    }
}
