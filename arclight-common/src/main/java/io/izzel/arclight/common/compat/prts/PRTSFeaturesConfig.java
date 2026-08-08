package io.izzel.arclight.common.compat.prts;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** PRTS 轻量防卡功能的配置。 */
public class PRTSFeaturesConfig {

    public static YamlConfiguration config;

    // EntityClear - 定时清理掉落物
    public static boolean clearItemEnabled;
    public static long clearItemInterval;
    public static List<String> clearItemWhitelist;
    public static String clearItemMsg;

    // EntityClear - 定时清理怪物（无自定义名）
    public static boolean clearMonsterEnabled;
    public static long clearMonsterInterval;
    public static List<String> clearMonsterWhitelist;
    public static String clearMonsterMsg;

    // Watchdog - 主线程卡顿看门狗（默认关；开启后主线程 > threshold-ms 未推进即告警并 dump 栈）
    public static boolean watchdogEnabled;
    public static long watchdogThresholdMs;
    public static long watchdogWarnCooldownMs;

    // Neighbor-update circuit breaker - 邻居更新风暴熔断（防看门狗强杀）
    public static boolean neighborUpdateBreakerEnabled;
    public static long neighborUpdateBreakerMaxPerTick;

    // ae2lt TeslaCoil setWorking 节流（缓解每 tick 翻转触发的邻居风暴）
    public static boolean ae2ltSetWorkingThrottleEnabled;
    public static int ae2ltSetWorkingThrottleMinTicks;

    // Parallel tick engine - P1 async pathfinding / P2 dimension / P3 region（PRTS 自研多线程引擎，默认全开）
    public static boolean parallelPathfindingAsync;
    public static boolean parallelDimension;
    public static boolean parallelRegion;
    /** P3 overworld region count (2/4/8, default 4; see docs v11). */
    public static int parallelRegionCount;
    /** P3 dynamic auto-scale (docs v12): periodically rebalance region count by load. */
    public static boolean regionAutoScale;
    public static long regionScaleIntervalSeconds;
    public static double regionScaleHighMspt;
    public static double regionScaleLowMspt;
    public static int regionScaleStablePeriods;
    public static int regionScaleMin;
    public static int regionScaleMax;
    public static double regionScaleCrossReadRatio;

    // Reliable chunk save - WAL 预写日志（PRTS 自研可靠区块保存，默认关）
    public static boolean reliableChunkSave;
    public static long journalIntervalSeconds;
    public static int journalChunksPerTick;

    public static void init() {
        File file = new File("prts-features.yml");
        config = YamlConfiguration.loadConfiguration(file);
        clearItemEnabled = config.getBoolean("entity-clear.item.enabled", false);
        clearItemInterval = config.getLong("entity-clear.item.interval-seconds", 300);
        clearItemWhitelist = new ArrayList<>(config.getStringList("entity-clear.item.whitelist"));
        clearItemMsg = config.getString("entity-clear.item.message", "");
        clearMonsterEnabled = config.getBoolean("entity-clear.monster.enabled", false);
        clearMonsterInterval = config.getLong("entity-clear.monster.interval-seconds", 600);
        clearMonsterWhitelist = new ArrayList<>(config.getStringList("entity-clear.monster.whitelist"));
        clearMonsterMsg = config.getString("entity-clear.monster.message", "");
        watchdogEnabled = config.getBoolean("watchdog.enabled", false);
        watchdogThresholdMs = config.getLong("watchdog.threshold-ms", 2000);
        watchdogWarnCooldownMs = config.getLong("watchdog.warn-cooldown-ms", 60000);
        neighborUpdateBreakerEnabled = config.getBoolean("neighbor-update-breaker.enabled", true);
        neighborUpdateBreakerMaxPerTick = config.getLong("neighbor-update-breaker.max-per-tick", 200000);
        ae2ltSetWorkingThrottleEnabled = config.getBoolean("ae2lt-setworking-throttle.enabled", true);
        ae2ltSetWorkingThrottleMinTicks = config.getInt("ae2lt-setworking-throttle.min-ticks", 4);
        parallelPathfindingAsync = config.getBoolean("parallel.pathfinding-async", true);
        parallelDimension = config.getBoolean("parallel.dimension-parallel", true);
        parallelRegion = config.getBoolean("parallel.region-parallel", true);
        int count = config.getInt("parallel.region-count", 4);
        if (count < 2) count = 2;
        if (count > 8) count = 8;
        if (Integer.bitCount(count) != 1) count = 4;
        parallelRegionCount = count;
        regionAutoScale = config.getBoolean("parallel.region-auto-scale", true);
        regionScaleIntervalSeconds = config.getLong("parallel.region-scale-interval-seconds", 300);
        regionScaleHighMspt = config.getDouble("parallel.region-scale-high-mspt", 60.0);
        regionScaleLowMspt = config.getDouble("parallel.region-scale-low-mspt", 15.0);
        regionScaleStablePeriods = Math.max(1, config.getInt("parallel.region-scale-stable-periods", 2));
        regionScaleMin = clampPower(config.getInt("parallel.region-scale-min", 2), 2, 8);
        regionScaleMax = clampPower(config.getInt("parallel.region-scale-max", 8), 2, 8);
        if (regionScaleMin > regionScaleMax) regionScaleMin = regionScaleMax;
        regionScaleCrossReadRatio = Math.max(0.0, config.getDouble("parallel.region-scale-cross-read-ratio", 0.05));
        reliableChunkSave = config.getBoolean("reliable-chunk-save.enabled", false);
        journalIntervalSeconds = config.getLong("reliable-chunk-save.interval-seconds", 30);
        journalChunksPerTick = config.getInt("reliable-chunk-save.chunks-per-tick", 50);
    }

    private static int clampPower(int v, int lo, int hi) {
        if (v <= 1) return lo;
        int p = 1;
        while (p < v) p <<= 1;
        return Math.max(lo, Math.min(hi, p));
    }
}
