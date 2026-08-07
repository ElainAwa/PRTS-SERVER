package io.izzel.arclight.common.compat.prts.feature;

import io.izzel.arclight.common.bridge.core.server.MinecraftServerBridge;
import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.mod.server.ArclightServer;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 主线程卡顿看门狗（移植自 Youer WatchMohist，已去 Youer 化）。 */
public class WatchMohist {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Watchdog");

    private static ScheduledThreadPoolExecutor executor;
    private static long lastTickTime = 0L;
    private static long lastWarnTime = 0L;
    private static Thread mainThread;

    public static void start() {
        if (!PRTSFeaturesConfig.watchdogEnabled) {
            return;
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "PRTS-Watchdog");
            t.setDaemon(true);
            return t;
        };
        executor = new ScheduledThreadPoolExecutor(1, tf);
        executor.scheduleAtFixedRate(WatchMohist::run, 60000L, 500L, TimeUnit.MILLISECONDS);
        LOGGER.info("[PRTS-Watchdog] started (threshold={}ms, cooldown={}ms)",
                PRTSFeaturesConfig.watchdogThresholdMs, PRTSFeaturesConfig.watchdogWarnCooldownMs);
    }

    public static void update() {
        if (!PRTSFeaturesConfig.watchdogEnabled) {
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
                && curTime - lastTickTime > PRTSFeaturesConfig.watchdogThresholdMs
                && curTime - lastWarnTime > PRTSFeaturesConfig.watchdogWarnCooldownMs) {
            lastWarnTime = curTime;
            LOGGER.warn("[PRTS-Watchdog] 主线程已超过 {}ms 未推进（疑似卡顿/假死），已卡 {}ms",
                    PRTSFeaturesConfig.watchdogThresholdMs, curTime - lastTickTime);
            double[] tps = ((MinecraftServerBridge) ArclightServer.getMinecraftServer()).arclight$getRecentTps();
            LOGGER.warn("[PRTS-Watchdog] 当前 TPS -> 1m={} 5m={} 15m={}",
                    String.format("%.2f", tps[0]), String.format("%.2f", tps[1]), String.format("%.2f", tps[2]));
            LOGGER.warn("[PRTS-Watchdog] 主线程栈顶（排查卡顿源）:");
            if (mainThread != null) {
                for (StackTraceElement stack : mainThread.getStackTrace()) {
                    LOGGER.warn("[PRTS-Watchdog]   at {}", stack);
                }
            }
        }
    }
}
