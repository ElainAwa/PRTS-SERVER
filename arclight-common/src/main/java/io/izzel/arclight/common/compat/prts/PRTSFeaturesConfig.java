package io.izzel.arclight.common.compat.prts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** PRTS 轻量防卡功能的配置。 */
public class PRTSFeaturesConfig {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Features");

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

    // Parallel tick engine - 异步寻路 / 维度并行 / 区域并行（PRTS 自研多线程引擎，默认全开）
    public static boolean parallelPathfindingAsync;
    public static boolean parallelDimension;
    public static boolean parallelRegion;
    /** 主世界区域数 (2/4/8, 默认 4)。 */
    public static int parallelRegionCount;
    /** 方块实体 tick 是否参与区域并行（默认关——BE 间交互复杂，并行有竞态风险）。 */
    public static boolean regionBlockEntityParallel;
    /** 主线程每 tick 处理的 chunk 需求上限（统一需求调度，默认 50）。 */
    public static int chunkDemandPerTick;
    /** 动态区域自动扩容：按负载周期性地调整区域数。 */
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

    // Generation task intake budget: caps how many pending chunk-generation tasks the
    // main thread hands to the worldgen mailbox per tick, spreading the intake storm;
    // 0 = unlimited (vanilla behavior).
    public static int generationTasksPerTick;

    // Chunkgen submission window: caps how many generation tasks are submitted within a
    // rolling 2s window so worldgen completions arrive at a steady rate instead of a
    // storm; 0 = unlimited.
    public static int chunkgenInflightLimit;

    // Barrier semantics: make the vanilla watchdog barrier-aware so a main thread waiting
    // in the dimension barrier is not falsely killed after max-tick-time; false = vanilla
    // watchdog behavior.
    public static boolean barrierWatchdogAware;
    /** Barrier await timeout ms; on expiry dump all threads and crash with a report. */
    public static long barrierTimeoutMs;

    public static void init() {
        File file = new File("prts-features.yml");
        if (!file.exists()) {
            writeDefaultConfig(file);
        }
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
        regionBlockEntityParallel = config.getBoolean("parallel.region-block-entity-parallel", false);
        chunkDemandPerTick = config.getInt("parallel.chunk-demand-per-tick", 50);
        // 非正数会使需求 drain 永久不执行（budget <= 0），退回默认值。
        if (chunkDemandPerTick < 1) chunkDemandPerTick = 50;
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.maxPerTick = chunkDemandPerTick;
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
        generationTasksPerTick = config.getInt("generation-tasks-per-tick", 50);
        if (generationTasksPerTick < 0) generationTasksPerTick = 0;
        chunkgenInflightLimit = config.getInt("chunkgen-inflight-limit", 128);
        if (chunkgenInflightLimit < 0) chunkgenInflightLimit = 0;
        barrierWatchdogAware = config.getBoolean("barrier-watchdog-aware", true);
        barrierTimeoutMs = config.getLong("barrier-timeout-ms", 120000L);
        if (barrierTimeoutMs < 1000L) barrierTimeoutMs = 120000L;
    }

    private static int clampPower(int v, int lo, int hi) {
        if (v <= 1) return lo;
        int p = 1;
        while (p < v) p <<= 1;
        return Math.max(lo, Math.min(hi, p));
    }

    /** 首次运行写出默认配置模板（带说明注释），后续修改需重启生效。 */
    private static void writeDefaultConfig(File file) {
        String template = """
                # PRTS 服务端功能配置（首次运行自动生成；修改后需重启生效）

                # 物品/怪物清理（默认全关）
                entity-clear:
                  item:
                    enabled: false
                    interval-seconds: 300
                    whitelist: []
                    message: ''
                  monster:
                    enabled: false
                    interval-seconds: 600
                    whitelist: []
                    message: ''

                # 服务器 watchdog（默认关）
                watchdog:
                  enabled: false
                  threshold-ms: 2000
                  warn-cooldown-ms: 60000

                # 邻居更新熔断（防百万级连锁更新风暴）
                neighbor-update-breaker:
                  enabled: true
                  max-per-tick: 200000

                # AE2LT SetWorking 节流
                ae2lt-setworking-throttle:
                  enabled: true
                  min-ticks: 4

                # 多线程并行引擎
                parallel:
                  pathfinding-async: true            # 异步寻路
                  dimension-parallel: true           # 维度并行
                  region-parallel: true              # 主世界区域并行（实体 tick）
                  region-block-entity-parallel: false # 方块实体 tick 并行（默认关：BE 间交互复杂有竞态）
                  chunk-demand-per-tick: 50           # 主线程每 tick 处理的 chunk 需求上限（统一需求调度）
                  region-count: 4                    # 区域数（2/4/8）
                  region-auto-scale: true            # 按负载自动调整区域数
                  region-scale-interval-seconds: 300
                  region-scale-high-mspt: 60.0
                  region-scale-low-mspt: 15.0
                  region-scale-stable-periods: 2
                  region-scale-min: 2
                  region-scale-max: 8
                  region-scale-cross-read-ratio: 0.05

                # 可靠区块保存（WAL 预写日志，默认关）
                reliable-chunk-save:
                  enabled: false
                  interval-seconds: 30
                  chunks-per-tick: 50

                # 区块生成削峰（0 = 关闭对应限制）
                generation-tasks-per-tick: 50        # 每 tick 提交预算
                chunkgen-inflight-limit: 128         # 滚动 2s 提交窗口（应 >= worldgen 能力）

                # Barrier 健壮性
                barrier-watchdog-aware: true         # watchdog 感知并行 barrier（防误杀）
                barrier-timeout-ms: 120000           # barrier 卡死超时（毫秒）
                """;
        try {
            Files.writeString(file.toPath(), template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write default prts-features.yml", e);
        }
    }
}
