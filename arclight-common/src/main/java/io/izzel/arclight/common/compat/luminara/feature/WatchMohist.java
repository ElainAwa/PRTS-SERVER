package io.izzel.arclight.common.compat.luminara.feature;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.compat.luminara.LuminaraFeaturesConfig;
import io.izzel.arclight.common.mod.server.ArclightServer;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 主线程卡顿看门狗（移植自 Youer WatchMohist，已去 Youer 化）。
 * 机制：每 tick 由 LuminaraFeatures.tick() 调用 update() 标记主线程存活；
 * 独立 daemon 线程每 500ms 检查，若主线程超过 threshold-ms 未推进（卡顿/假死）则告警并 dump 主线程栈。
 * 依赖：仅 Luminara config + log4j + Bukkit TPS，无 Youer 任何类。
 */
public class WatchMohist {

    private static final Logger LOGGER = LogManager.getLogger("Luminara-Watchdog");

    private static ScheduledThreadPoolExecutor executor;
    private static long lastTickTime = 0L;
    private static long lastWarnTime = 0L;
    private static Thread mainThread;

    public static void start() {
        if (!LuminaraFeaturesConfig.watchdogEnabled) {
            return;
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "Luminara-Watchdog");
            t.setDaemon(true);
            return t;
        };
        executor = new ScheduledThreadPoolExecutor(1, tf);
        executor.scheduleAtFixedRate(WatchMohist::run, 60000L, 500L, TimeUnit.MILLISECONDS);
        LOGGER.info("[Luminara-Watchdog] started (threshold={}ms, cooldown={}ms)",
                LuminaraFeaturesConfig.watchdogThresholdMs, LuminaraFeaturesConfig.watchdogWarnCooldownMs);
    }

    public static void update() {
        if (!LuminaraFeaturesConfig.watchdogEnabled) {
            return;
        }
        lastTickTime = System.currentTimeMillis();
        mainThread = Thread.currentThread();
    }

    public static void stop() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private static void run() {
        long curTime = System.currentTimeMillis();
        if (lastTickTime > 0L
                && curTime - lastTickTime > LuminaraFeaturesConfig.watchdogThresholdMs
                && curTime - lastWarnTime > LuminaraFeaturesConfig.watchdogWarnCooldownMs) {
            lastWarnTime = curTime;
            LOGGER.warn("[Luminara-Watchdog] 主线程已超过 {}ms 未推进（疑似卡顿/假死），已卡 {}ms",
                    LuminaraFeaturesConfig.watchdogThresholdMs, curTime - lastTickTime);
            double[] tps = ((MinecraftServerBridge) ArclightServer.getMinecraftServer()).arclight$getRecentTps();
            LOGGER.warn("[Luminara-Watchdog] 当前 TPS -> 1m={} 5m={} 15m={}",
                    String.format("%.2f", tps[0]), String.format("%.2f", tps[1]), String.format("%.2f", tps[2]));
            LOGGER.warn("[Luminara-Watchdog] 主线程栈顶（排查卡顿源）:");
            if (mainThread != null) {
                for (StackTraceElement stack : mainThread.getStackTrace()) {
                    LOGGER.warn("[Luminara-Watchdog]   at {}", stack);
                }
            }
        }
    }
}
