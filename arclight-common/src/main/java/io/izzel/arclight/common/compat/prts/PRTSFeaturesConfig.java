package io.izzel.arclight.common.compat.prts;

import io.izzel.arclight.common.optimization.general.servercore.ownership.ClassAffinityLedger;
import io.izzel.arclight.common.optimization.general.servercore.ownership.ThreadPolicy;
import io.izzel.arclight.common.optimization.general.servercore.ownership.WorldAccessGuard;
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
    /** 殖民地 NPC 工作 AI 相位错峰（按 citizenId 摊平每 5 tick 的集中尖峰）。 */
    public static boolean parallelColonyPhaseStagger;
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
    /** worker 世界访问策略：off（关闭）/ stats（只统计，默认）/ enforce（测服定位用）。 */
    public static ThreadPolicy threadPolicy;
    /** 违规日志每分钟每类限流条数（>0）。 */
    public static int violationLogPerMinute;
    /** 自动路由：auto（违规学习）/ manual（只认前缀种子 + force/allow 列表）。 */
    public static String mainThreadRouting;
    /** 时间窗内 MAIN_ONLY 违规达到该次数即把实体类路由主线程（0 = 禁用学习）。 */
    public static int routeThreshold;
    /** 违规学习窗口（tick，默认 2400 = 2 分钟）。 */
    public static long routeWindowTicks;
    /** 强制主线程 tick 的类名/前缀（优先级最高）。 */
    public static List<String> mainThreadEntityForce;
    /** 强制不路由的类名/前缀（危险调试用，覆盖学习与种子）。 */
    public static List<String> mainThreadEntityAllow;
    /** 优雅停机时把本会话学到的路由追加写回配置文件（默认关，后续版本启用）。 */
    public static boolean persistLearnedRoutes;
    /** 跨区写 journal 每区域队列上限（最旧条目丢弃，默认 4096）。 */
    public static int journalMaxPerRegion;
    /** read-your-writes overlay 总开关（默认关，仅预留接口，未接入方块读路径）。 */
    public static boolean journalReadBack;
    /** BE 三档调度：允许在区域 worker 上 tick 的方块实体类型（registry key 或前缀*）。 */
    public static List<String> beParallelAllow;
    /** BE 三档调度：强制主线程 tick 的方块实体类型（优先级最高）。 */
    public static List<String> beMainThreadForce;
    /** Create 长轨道假轨光栅化分摊（默认关）：每 tick 处理一条连接的一个区块。 */
    public static boolean createTrackLazySpread;
    /** 分摊时单连接每 tick 最多处理的栅格块数（默认 64）。 */
    public static int createTrackLazyChunkBlocks;

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

    // Lighting - per-tick light propagation budget + telemetry (PRTS 光照预算化).
    // 限制每 tick 光照传播工作量，风暴时超出部分顺延下一 tick（最终光照一致，只是延迟）。
    public static boolean lightBudgetEnabled;
    /** 每 tick 最多传播的方块数（0 = 不限/vanilla）。 */
    public static int lightBudgetPerTick;
    /** 采集光照队列长度/耗时进 AsyncTaskStats（[light-engine] 日志）。 */
    public static boolean lightTelemetryEnabled;

    // Entity spatial index - EntitySection 内懒 4×4×4 子格索引（默认开，2026-08-16 真机 A/B 验证）。
    // 只加速纯空间 AABB 查询（getEntities(AABB)）；typed 查询/玩家交互路径不动；
    // 返回顺序与原版一致（插入序号排序）；对 Lithium/Canary/Radium/Recruits 让位。
    public static boolean entitySpatialIndexEnabled;
    /** section 实体数达到该值才建索引（小 section 走原版线性扫描，零成本）。 */
    public static int entitySpatialIndexMinSectionSize;
    /** 采集查询/候选数进 AsyncTaskStats（[entity-spatial-index] 日志）。 */
    public static boolean entitySpatialIndexTelemetryEnabled;

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
        parallelColonyPhaseStagger = config.getBoolean("parallel.colony-npc-phase-stagger", true);
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
        // worker 世界访问策略：解析失败/未知值回退 stats（只统计不拦截，生产安全）。
        // YAML 会把裸写的 off/on 解析成布尔值，这里先还原成字符串再交给 ThreadPolicy。
        Object threadPolicyValue = config.get("parallel.thread-policy", "stats");
        String threadPolicyRaw;
        if (threadPolicyValue instanceof Boolean bool) {
            threadPolicyRaw = bool ? "on" : "off";
        } else {
            threadPolicyRaw = String.valueOf(threadPolicyValue);
        }
        threadPolicy = ThreadPolicy.parse(threadPolicyRaw);
        LOGGER.info("parallel.thread-policy raw={} parsed={}", threadPolicyRaw, threadPolicy);
        if (threadPolicy == ThreadPolicy.ENFORCE) {
            LOGGER.warn("parallel.thread-policy=enforce is for test-server debugging only; violations will abort the offending entity tick");
        }
        violationLogPerMinute = Math.max(1, config.getInt("parallel.violation-log-per-minute", 20));
        WorldAccessGuard.applyConfig(threadPolicy, violationLogPerMinute);
        mainThreadRouting = config.getString("parallel.main-thread-routing", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(mainThreadRouting) && !"manual".equals(mainThreadRouting)) {
            LOGGER.warn("parallel.main-thread-routing={} is invalid; falling back to auto", mainThreadRouting);
            mainThreadRouting = "auto";
        }
        routeThreshold = Math.max(0, config.getInt("parallel.route-threshold", 2));
        routeWindowTicks = Math.max(20, config.getLong("parallel.route-window-ticks", 2400));
        mainThreadEntityForce = new ArrayList<>(config.getStringList("parallel.main-thread-entity-force"));
        mainThreadEntityAllow = new ArrayList<>(config.getStringList("parallel.main-thread-entity-allow"));
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", false);
        ClassAffinityLedger.applyConfig(routeThreshold, routeWindowTicks);
        LOGGER.info("parallel main-thread-routing={} threshold={} window={} ticks force={} allow={} persist={}",
                mainThreadRouting, routeThreshold, routeWindowTicks,
                mainThreadEntityForce.size(), mainThreadEntityAllow.size(), persistLearnedRoutes);
        journalMaxPerRegion = Math.max(16, config.getInt("parallel.journal-max-per-region", 4096));
        journalReadBack = config.getBoolean("parallel.journal-read-back", false);
        beParallelAllow = new ArrayList<>(config.getStringList("parallel.be-parallel-allow"));
        beMainThreadForce = new ArrayList<>(config.getStringList("parallel.be-main-thread-force"));
        if (beMainThreadForce.isEmpty()) {
            // spike 实测：create:track 单次 tick 最大 342ms（列车图全局计算），铁主线程。
            beMainThreadForce.add("create:track");
        }
        createTrackLazySpread = config.getBoolean("parallel.create-track-lazy-spread", false);
        createTrackLazyChunkBlocks = Math.max(8, Math.min(512, config.getInt("parallel.create-track-lazy-chunk-blocks", 64)));
        LOGGER.info("parallel be-policy allow={} force={} parallelEnabled={} trackLazySpread={} chunk={}",
                beParallelAllow, beMainThreadForce, regionBlockEntityParallel,
                createTrackLazySpread, createTrackLazyChunkBlocks);
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
        lightBudgetEnabled = config.getBoolean("lighting.budget-enabled", true);
        lightBudgetPerTick = config.getInt("lighting.budget-per-tick", 100000);
        if (lightBudgetPerTick < 0) lightBudgetPerTick = 0;
        lightTelemetryEnabled = config.getBoolean("lighting.telemetry-enabled", true);
        entitySpatialIndexEnabled = config.getBoolean("entity-spatial-index.enabled", true);
        entitySpatialIndexMinSectionSize = config.getInt("entity-spatial-index.min-section-size", 16);
        if (entitySpatialIndexMinSectionSize < 4) entitySpatialIndexMinSectionSize = 4;
        entitySpatialIndexTelemetryEnabled = config.getBoolean("entity-spatial-index.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.entityspatial.EntitySpatialIndexStats.setEnabled(entitySpatialIndexTelemetryEnabled);
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
                  colony-npc-phase-stagger: true      # 殖民地 NPC 工作 AI 相位错峰（默认开）
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
                  thread-policy: stats             # worker 世界访问策略: off/stats/enforce（生产用 stats）
                  violation-log-per-minute: 20     # 违规日志每分钟每类限流条数
                  main-thread-routing: auto        # auto 违规学习 / manual 只认种子列表
                  route-threshold: 2               # 窗口内 MAIN_ONLY 违规次数即路由主线程（0=禁用学习）
                  route-window-ticks: 2400         # 违规学习窗口（tick，2400=2分钟）
                  main-thread-entity-force: []     # 强制主线程 tick 的类名/前缀
                  main-thread-entity-allow: []     # 强制不路由的类名/前缀（危险调试用）
                  persist-learned-routes: false    # 停机时把学到的路由写回配置（暂未实现）
                  journal-max-per-region: 4096     # 跨区写 journal 每区域队列上限（最旧丢弃）
                  journal-read-back: false         # read-your-writes overlay（预留接口，默认关）
                  be-parallel-allow: []            # BE 三档：允许 region worker tick 的类型（registry key 或前缀*）
                  be-main-thread-force: ["create:track"] # BE 三档：强制主线程类型（尖峰/跨区依赖）
                  create-track-lazy-spread: false   # Create 长轨道假轨光栅化分摊（默认关）
                  create-track-lazy-chunk-blocks: 64 # 分摊时单连接每 tick 最多栅格块数

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

                # 光照：每 tick 传播预算 + 遥测（1.21.1 光照传播在光线程异步执行）
                # 预算限制每 tick 传播工作量，风暴（大量方块变更）时超出部分顺延下一 tick；
                # 最终光照一致，只是延迟，mod 无感知。0 = 不限（vanilla）。
                lighting:
                  budget-enabled: true               # 每 tick 光照传播预算开关
                  budget-per-tick: 100000            # 每 tick 最多传播的方块数（默认保守，只拦风暴；按 [light-engine] 日志调）
                  telemetry-enabled: true            # 采集队列长度/耗时进 [light-engine] 日志

                # 实体空间索引：EntitySection 内懒 4×4×4 子格索引（默认开）
                # 只加速纯空间 AABB 查询（getEntities(AABB)），typed getEntitiesOfClass 不动；
                # 返回顺序与原版一致（插入序号排序）；对 Lithium/Canary/Radium/Recruits 让位。
                # 2026-08-16 真机 A/B：120 只僵尸高密度场景 avg mspt 5.1→3.4ms（-33%）。
                entity-spatial-index:
                  enabled: true                      # 默认开（可随时关；异常时看 [entity-spatial-index] 日志）
                  min-section-size: 16               # section 实体数达到该值才建索引（小 section 走原版线性扫描）
                  telemetry-enabled: true            # 采集查询/候选数进 [entity-spatial-index] 日志
                """;
        try {
            Files.writeString(file.toPath(), template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write default prts-features.yml", e);
        }
    }
}
