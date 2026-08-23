package io.izzel.arclight.common.compat.prts;

import io.izzel.arclight.common.optimization.general.servercore.ownership.ClassAffinityLedger;
import io.izzel.arclight.common.optimization.general.servercore.ownership.ThreadPolicy;
import io.izzel.arclight.common.optimization.general.servercore.ownership.WorldAccessGuard;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    /** 殖民地 NPC 工作 AI 执行间隔（tick）。原版固定 5；2-60 可调。 */
    public static int colonyNpcWorkInterval;
    /** 村民主线程 POI / 单目标寻路预算（每 tick；0 = 关闭）。 */
    public static int villagerPoiPathBudget;
    /** S2.9.2: 主线程 routed entity drain 每 tick 处理上限（0 = 不分批，全量 drain）。 */
    public static int mainThreadEntityDrainBudget;
    /** S2.5 P1: 主线程单目标移动寻路异步化（routed villager 主线程 A* 削峰；默认关）。 */
    public static boolean mainThreadPathAsync;
    /** S3.1: learned routes JSON 文件路径。 */
    public static String learnedRoutesFile;
    /** S3.1: 最多持久化的 learned routes 数量。 */
    public static int learnedRoutesLimit;
    /** S3.2: 启用双向 probation（自动路由类定期试跑 worker，无违规则解除路由）。 */
    public static boolean routeProbationEnabled;
    /** S3.2: probation 间隔（tick）。 */
    public static int routeProbationTicks;
    /** S3.2: 允许 probation 的最大历史违规数（超过此值的类不做 probation）。 */
    public static int routeProbationMaxViolations;
    /** 殖民地管理器 ServerTick 事件里的 getAllColonies 快照缓存（默认关；测试服 A/B 后定默认）。 */
    public static boolean colonyManagerTickCacheEnabled;
    /** 殖民地列表快照的 TTL（tick）；过期自动重建，create/delete 路径立即失效。 */
    public static int colonyManagerTickCacheInterval;
    /** EventBus 事件分发遥测（诊断用，默认关；开启后会给每个事件类挂 HIGHEST/LOWEST 计时监听器）。 */
    public static boolean eventBusTelemetryEnabled;
    /** 主线程每 tick 处理的 chunk 需求上限（统一需求调度，默认 50）。 */
    public static int chunkDemandPerTick;
    /** S2.75 P0-b: chunk 需求玩家距离优先级（分 4 桶优先消费，默认关）。 */
    public static boolean chunkDemandPlayerPriority;
    /** 低优先级桶队头超龄即优先消费的阈值（tick，饿死兜底）。 */
    public static int chunkDemandStarveTicks;
    /** 动态区域自动扩容：按负载周期性地调整区域数。 */
    public static boolean regionAutoScale;
    public static long regionScaleIntervalSeconds;
    public static double regionScaleHighMspt;
    public static double regionScaleLowMspt;
    public static int regionScaleStablePeriods;
    public static int regionScaleMin;
    public static int regionScaleMax;
    public static double regionScaleCrossReadRatio;
    /** S4 不等宽条带：组内边界重平衡总开关（默认关）。 */
    public static boolean unevenStripes;
    public static long rebalanceIntervalSeconds;
    public static int rebalanceMaxMoves;
    public static int rebalanceMinGroups;
    public static double rebalanceImbalanceRatio;
    /** worker 世界访问策略：off（关闭）/ stats（只统计，默认）/ enforce（测服定位用）。 */
    public static ThreadPolicy threadPolicy;
    /** 违规日志每分钟每类限流条数（>0）。 */
    public static int violationLogPerMinute;
    /** [诊断] MAIN_ONLY 违规调用栈追踪：owner 类名含该子串时抓一次栈（空=关闭，默认关）。 */
    public static String threadPolicyTraceClass;
    /** 自动路由：auto（违规学习）/ manual（只认前缀种子 + force/allow 列表）。 */
    public static String mainThreadRouting;
    /** 时间窗内 MAIN_ONLY 违规达到该次数即把实体类路由主线程（0 = 禁用学习）。 */
    public static int routeThreshold;
    /** 违规学习窗口（tick，默认 2400 = 2 分钟）。 */
    public static long routeWindowTicks;
    /** MAIN_ONLY_READ 是否计入 auto-route 窗口。worker 读 BE 恒返回 null（vanilla 语义），
     *  默认 true = 保持现有行为；false = 只按写路由（读为 null-安全降级，不路由）。 */
    public static boolean routeOnRead;
    /** 跨区引用探针：采样 worker 对方块实体的访问，按调用方分桶成区内/跨区（默认关）。 */
    public static boolean crossrefProbe;
    /** 值快照：探针命中容器类方块实体时读时复制，做影子验证（默认关）。 */
    public static boolean crossrefValueSnapshot;
    /** B4: worker 上实体撞 Create 传送带时把 passenger 注册延迟到主线程执行，
     *  使 villager 等回并行的实体仍能被传送带运输。默认 false（配合 route-on-read=false 使用）。 */
    public static boolean beltPassengerDefer;
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
    /** 确定性模式：跨区 journal 按区域序在主调度线程统一应用（默认关）。 */
    public static boolean determinismMode;
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
    /** S2.75 P0-a: 独立光照线程——light 邮箱 + 任务排序器迁出共享后台池，
     *  隔离光照传播与 worldgen 线程池争抢（每维度一个守护线程，默认关）。 */
    public static boolean lightThreadEnabled;

    // Entity spatial index - EntitySection 内懒 4×4×4 子格索引（默认开，2026-08-16 真机 A/B 验证）。
    // 加速纯空间 AABB 查询（getEntities(AABB)）与 typed 查询（getEntitiesOfClass 等，二期：
    // vanilla 类列表按覆盖格子预筛，顺序/语义与原版逐位一致）；玩家交互路径不动；
    // 返回顺序与原版一致（插入序号排序）；对 Lithium/Canary/Radium/Recruits 让位。
    public static boolean entitySpatialIndexEnabled;
    /** section 实体数达到该值才建索引（小 section 走原版线性扫描，零成本）。 */
    public static int entitySpatialIndexMinSectionSize;
    /** 采集查询/候选数进 AsyncTaskStats（[entity-spatial-index] 日志）。 */
    public static boolean entitySpatialIndexTelemetryEnabled;

    // POI query fast path - PoiManager.getInChunk 空 chunk 存在性预检（默认开，2026-08-16 落地）。
    // 1.21.1 的 PoiSection 已按 PoiType 分桶（vanilla 自带），剩余成本 = 查询范围内大量无 POI
    // 区块的全垂直 section 扫描；本优化维护「区块是否有 POI」位掩码，无 POI 区块直接跳过。
    // 语义零变化（跳过空区块 = 结果集不变）；冷区块（未读盘）保持原版 getOrLoad 语义。
    public static boolean poiQueryEnabled;
    /** 采集命中/跳过计数进 AsyncTaskStats（[poi-query] 日志）。 */
    public static boolean poiQueryTelemetryEnabled;

    // Collision batch - Entity.collide 上台阶分支二次收集去重（默认开，2026-08-16 落地）。
    // 1.21.1 collideBoundingBox 已「一次收集、逐轴 clip」；但 step-up 分支会对扩展区域再次
    // 全量 collectColliders（走路生物每次地面移动都付）。本优化在同一 collide 帧内缓存首次
    // 收集结果，step-up 只增量补取顶部条带。语义零变化（补集合并 = 全量结果）。
    // 对 Lithium/Canary/Radium 让位（它们重写同一条碰撞路径）。
    public static boolean collisionBatchEnabled;
    /** 采集命中/增量/全量计数进 AsyncTaskStats（[collision-batch] 日志）。 */
    public static boolean collisionBatchTelemetryEnabled;

    // Menu broadcast precheck - AbstractContainerMenu.broadcastChanges 全等预检短路（P3，默认关）。
    // 1.21.1 的 broadcastChanges 每 tick 对每个打开菜单全量遍历所有槽位：每槽 getItem +
    // requireNonNull + memoize lambda 分配，随后 triggerSlotListeners/synchronizeSlotToRemote
    // 内部都做 lastSlots diff（无变化时纯浪费，但 lambda 已分配）。本优化在 HEAD 用与原版
    // 逐条等价的判定（lastSlots/remoteCarried/dataSlots 值缓存）预检：全部相等 = 原版循环
    // 必然无动作，直接跳过整个循环。语义逐位一致（预检不是脏槽跟踪，是全量 diff 的提前
    // 等价物——mod 直写容器同样被同一 diff 捕获，零漏检）。默认关：P3 定位「先实测归因」，
    // 生产服 spark 确认 broadcastChanges 子树占比后再开。
    public static boolean menuBroadcastEnabled;
    /** 采集短路/全量/槽位检查数进 AsyncTaskStats（[menu-broadcast] 日志）。 */
    public static boolean menuBroadcastTelemetryEnabled;

    // Event bridge on-demand registration (P0-1, 2026-08-17 计划稿 §四).
    // Arclight 的 5 个 Forge 桥 dispatcher 从「启动时无条件注册」改为按「有插件在听对应
    // Bukkit 事件」按需注册/注销（SimplePluginManager 注册/注销路径挂钩，0→1 注册、1→0
    // 注销）。无插件监听的服务器上 Forge 事件照发、mod 监听器照收，只是桥自己的监听器
    // 不在总线上——桥开销（CraftBlock/事件构造 + 空派发 + 回写）整块归零。
    // P0-2 防御层：dispatcher 入口 O(1) 空监听器预检（HandlerList 空则跳过构造+派发）。
    // 兼容红线：事件数量与时机零变化（只动 Arclight 自己的监听器是否在总线）。
    public static boolean eventBridgeOnDemandEnabled;
    /** 恢复 mod 加载期常驻注册（顺序敏感场景的逃生门）。 */
    public static boolean eventBridgeEagerRegistration;
    /** 采集转发/跳过/注册注销计数进 [event-bridge] 日志。 */
    public static boolean eventBridgeTelemetryEnabled;

    // Event short-circuit (P1-3/P1-4, 2026-08-17 计划稿 §8.5).
    // EntityTickEvent（每实体每 tick ×2，频率之王）与 NeighborNotifyEvent（结果被丢弃）
    // 在无监听器时短路掉事件构造与 post——零语义风险（无监听器 = Pre 恒未取消 = tick 照跑；
    // onNeighborNotify 的 isCanceled 结果原代码直接丢弃）。
    public static boolean eventShortcircuitEntityTickEnabled;
    public static boolean eventShortcircuitNeighborNotifyEnabled;
    /** 采集短路/转发计数进 [event-shortcircuit] 日志。 */
    public static boolean eventShortcircuitTelemetryEnabled;
    /** P1-2 直接调用点短路：callBlockFormEvent（BlockFormEvent/EntityBlockFormEvent）无监听器时返回 null。 */
    public static boolean eventShortcircuitBlockFormEnabled;
    /** P2-3 刷怪事件短路：MobSpawnEvent.PositionCheck / MobDespawnEvent 无监听器时跳过构造与派发（内联原版判定结果）。 */
    public static boolean eventShortcircuitMobSpawnEnabled;

    /** 幂等守卫：主维度 ChunkMap 在 createLevels HEAD 就需要本配置，
     *  早加载后 PRTSFeatures.start() 再次调用直接跳过。 */
    private static boolean initialized;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
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
        eventBusTelemetryEnabled = config.getBoolean("eventbus.telemetry-enabled", false);
        parallelPathfindingAsync = config.getBoolean("parallel.pathfinding-async", true);
        parallelDimension = config.getBoolean("parallel.dimension-parallel", true);
        parallelRegion = config.getBoolean("parallel.region-parallel", true);
        regionBlockEntityParallel = config.getBoolean("parallel.region-block-entity-parallel", false);
        parallelColonyPhaseStagger = config.getBoolean("parallel.colony-npc-phase-stagger", true);
        colonyNpcWorkInterval = Math.max(1, Math.min(60, config.getInt("parallel.colony-npc-work-interval", 5)));
        colonyManagerTickCacheEnabled = config.getBoolean("parallel.colony-manager-tick-cache-enabled", true);
        colonyManagerTickCacheInterval = Math.max(1, Math.min(120, config.getInt("parallel.colony-manager-tick-cache-interval", 20)));
        villagerPoiPathBudget = Math.max(0, config.getInt("parallel.villager-poi-path-budget", 0));
        mainThreadPathAsync = config.getBoolean("parallel.main-thread-path-async", false);
        mainThreadEntityDrainBudget = Math.max(0, config.getInt("parallel.main-thread-entity-drain-budget", 0));
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", false);
        learnedRoutesFile = config.getString("parallel.learned-routes-file", "config/prts-learned-routes.json");
        learnedRoutesLimit = Math.max(1, config.getInt("parallel.learned-routes-limit", 200));
        routeProbationEnabled = config.getBoolean("parallel.route-probation-enabled", false);
        routeProbationTicks = Math.max(100, config.getInt("parallel.route-probation-ticks", 12000));
        routeProbationMaxViolations = Math.max(0, config.getInt("parallel.route-probation-max-violations", 2));
        chunkDemandPerTick = config.getInt("parallel.chunk-demand-per-tick", 50);
        // 非正数会让需求 drain 永久不执行（budget <= 0），退回默认值。
        if (chunkDemandPerTick < 1) chunkDemandPerTick = 50;
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.maxPerTick = chunkDemandPerTick;
        chunkDemandPlayerPriority = config.getBoolean("parallel.chunk-demand-player-priority", false);
        chunkDemandStarveTicks = Math.max(20, config.getInt("parallel.chunk-demand-starve-ticks", 600));
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.playerPriorityEnabled = chunkDemandPlayerPriority;
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.starveNanos = chunkDemandStarveTicks * 50_000_000L;
        LOGGER.info("parallel chunk-demand priority={} starve={} ticks", chunkDemandPlayerPriority, chunkDemandStarveTicks);
        int count = config.getInt("parallel.region-count", 4);
        if (count < 2) count = 2;
        if (count > 16) count = 16;
        if (Integer.bitCount(count) != 1) count = 4;
        parallelRegionCount = count;
        regionAutoScale = config.getBoolean("parallel.region-auto-scale", true);
        regionScaleIntervalSeconds = config.getLong("parallel.region-scale-interval-seconds", 300);
        regionScaleHighMspt = config.getDouble("parallel.region-scale-high-mspt", 60.0);
        regionScaleLowMspt = config.getDouble("parallel.region-scale-low-mspt", 15.0);
        regionScaleStablePeriods = Math.max(1, config.getInt("parallel.region-scale-stable-periods", 2));
        regionScaleMin = clampPower(config.getInt("parallel.region-scale-min", 2), 2, 16);
        regionScaleMax = clampPower(config.getInt("parallel.region-scale-max", 8), 2, 16);
        if (regionScaleMin > regionScaleMax) regionScaleMin = regionScaleMax;
        regionScaleCrossReadRatio = Math.max(0.0, config.getDouble("parallel.region-scale-cross-read-ratio", 0.05));
        unevenStripes = config.getBoolean("parallel.uneven-stripes", false);
        rebalanceIntervalSeconds = config.getLong("parallel.rebalance-interval-seconds", 300);
        rebalanceMaxMoves = Math.max(1, config.getInt("parallel.rebalance-max-moves", 1));
        rebalanceMinGroups = Math.max(1, config.getInt("parallel.rebalance-min-groups", 1));
        rebalanceImbalanceRatio = Math.max(1.0, config.getDouble("parallel.rebalance-imbalance-ratio", 2.0));
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
        threadPolicyTraceClass = config.getString("parallel.thread-policy-trace-class", "").trim();
        WorldAccessGuard.applyConfig(threadPolicy, violationLogPerMinute);
        WorldAccessGuard.setTraceClass(threadPolicyTraceClass);
        mainThreadRouting = config.getString("parallel.main-thread-routing", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(mainThreadRouting) && !"manual".equals(mainThreadRouting)) {
            LOGGER.warn("parallel.main-thread-routing={} is invalid; falling back to auto", mainThreadRouting);
            mainThreadRouting = "auto";
        }
        routeThreshold = Math.max(0, config.getInt("parallel.route-threshold", 5));
        routeWindowTicks = Math.max(20, config.getLong("parallel.route-window-ticks", 2400));
        routeOnRead = config.getBoolean("parallel.route-on-read", true);
        beltPassengerDefer = config.getBoolean("parallel.belt-passenger-defer", false);
        mainThreadEntityForce = new ArrayList<>(config.getStringList("parallel.main-thread-entity-force"));
        mainThreadEntityAllow = new ArrayList<>(config.getStringList("parallel.main-thread-entity-allow"));
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", false);
        ClassAffinityLedger.applyConfig(routeThreshold, routeWindowTicks, routeOnRead);
        crossrefProbe = config.getBoolean("parallel.crossref-probe", false);
        crossrefValueSnapshot = config.getBoolean("parallel.crossref-value-snapshot", false);
        io.izzel.arclight.common.optimization.general.servercore.ownership.CrossRefProbe.applyConfig(crossrefProbe, crossrefValueSnapshot);
        LOGGER.info("parallel crossref-probe={} value-snapshot={}", crossrefProbe, crossrefValueSnapshot);
        LOGGER.info("parallel main-thread-routing={} threshold={} window={} ticks force={} allow={} persist={}",
                mainThreadRouting, routeThreshold, routeWindowTicks,
                mainThreadEntityForce.size(), mainThreadEntityAllow.size(), persistLearnedRoutes);
        journalMaxPerRegion = Math.max(16, config.getInt("parallel.journal-max-per-region", 4096));
        journalReadBack = config.getBoolean("parallel.journal-read-back", false);
        determinismMode = config.getBoolean("parallel.determinism-mode", false);
        beParallelAllow = new ArrayList<>(config.getStringList("parallel.be-parallel-allow"));
        beMainThreadForce = new ArrayList<>(config.getStringList("parallel.be-main-thread-force"));
        if (beMainThreadForce.isEmpty()) {
            // spike 实测：create:track 单次 tick 最大 342ms（列车图全局计算），铁主线程。
            beMainThreadForce.add("create:track");
            // 灰度实测：lootr:lootr_chest 在 worker 上复现 ReportedException
            // （08-16 13:18），安全阀兜住后永久 unsafe——默认直接锁主线程。
            beMainThreadForce.add("lootr:lootr_chest");
        }
        createTrackLazySpread = config.getBoolean("parallel.create-track-lazy-spread", false);
        createTrackLazyChunkBlocks = Math.max(8, Math.min(512, config.getInt("parallel.create-track-lazy-chunk-blocks", 64)));
        LOGGER.info("parallel be-policy allow={} force={} parallelEnabled={} trackLazySpread={} chunk={} villagerPoiBudget={}",
                beParallelAllow, beMainThreadForce, regionBlockEntityParallel,
                createTrackLazySpread, createTrackLazyChunkBlocks, villagerPoiPathBudget);
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
        lightThreadEnabled = config.getBoolean("lighting.threaded", false);
        entitySpatialIndexEnabled = config.getBoolean("entity-spatial-index.enabled", true);
        entitySpatialIndexMinSectionSize = config.getInt("entity-spatial-index.min-section-size", 16);
        if (entitySpatialIndexMinSectionSize < 4) entitySpatialIndexMinSectionSize = 4;
        entitySpatialIndexTelemetryEnabled = config.getBoolean("entity-spatial-index.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.entityspatial.EntitySpatialIndexStats.setEnabled(entitySpatialIndexTelemetryEnabled);
        poiQueryEnabled = config.getBoolean("poi-query.enabled", true);
        poiQueryTelemetryEnabled = config.getBoolean("poi-query.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.poi.PoiQueryStats.setEnabled(poiQueryTelemetryEnabled);
        collisionBatchEnabled = config.getBoolean("collision-batch.enabled", true);
        collisionBatchTelemetryEnabled = config.getBoolean("collision-batch.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.collision.CollisionBatchStats.setEnabled(collisionBatchTelemetryEnabled);
        menuBroadcastEnabled = config.getBoolean("menu-broadcast.enabled", false);
        menuBroadcastTelemetryEnabled = config.getBoolean("menu-broadcast.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.menubroadcast.MenuBroadcastStats.setEnabled(menuBroadcastTelemetryEnabled);
        eventBridgeOnDemandEnabled = config.getBoolean("event-bridge.on-demand-registration.enabled", true);
        eventBridgeEagerRegistration = config.getBoolean("event-bridge.on-demand-registration.eager-registration", false);
        eventBridgeTelemetryEnabled = config.getBoolean("event-bridge.on-demand-registration.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeStats.setEnabled(eventBridgeTelemetryEnabled);
        // enabled=false 或 eager-registration=true 都恢复「启动即全注册」旧行为；否则按门收敛。
        io.izzel.arclight.common.optimization.general.eventbridge.EventBridgeRegistry.setActive(
                eventBridgeOnDemandEnabled && !eventBridgeEagerRegistration);
        eventShortcircuitEntityTickEnabled = config.getBoolean("event-shortcircuit.entity-tick-event.enabled", true);
        eventShortcircuitNeighborNotifyEnabled = config.getBoolean("event-shortcircuit.neighbor-notify-event.enabled", true);
        eventShortcircuitTelemetryEnabled = config.getBoolean("event-shortcircuit.telemetry-enabled", true);
        eventShortcircuitBlockFormEnabled = config.getBoolean("event-shortcircuit.block-form-event.enabled", true);
        eventShortcircuitMobSpawnEnabled = config.getBoolean("event-shortcircuit.mob-spawn-event.enabled", true);
        io.izzel.arclight.common.optimization.general.eventbridge.EventShortcircuitStats.setEnabled(eventShortcircuitTelemetryEnabled);
        LOGGER.info("event-bridge on-demand={} eager={} | event-shortcircuit entityTick={} neighborNotify={}",
                eventBridgeOnDemandEnabled, eventBridgeEagerRegistration,
                eventShortcircuitEntityTickEnabled, eventShortcircuitNeighborNotifyEnabled);
    }

    /** S3: Persist learned routes to independent JSON file (replaces old YAML append logic). */
    public static void persistLearnedRoutes() {
        try {
            io.izzel.arclight.common.optimization.general.servercore.ownership.LearnedRoutePersistence.saveOnShutdown();
        } catch (Exception e) {
            LOGGER.error("persist-learned-routes failed", e);
        }
    }

    private static List<String> parseInlineList(String value) {
        String text = value.strip();
        List<String> result = new ArrayList<>();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return result;
        }
        text = text.substring(1, text.length() - 1);
        if (text.isBlank()) {
            return result;
        }
        for (String part : text.split(",")) {
            String item = part.strip().replace("\"", "").replace("'", "");
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
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

                # EventBus 分发遥测（诊断用；会给每个事件类挂 HIGHEST/LOWEST 计时监听器，默认关）
                eventbus:
                  telemetry-enabled: false

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
                  colony-npc-work-interval: 5        # 殖民地 NPC 工作 AI 执行间隔 tick（原版 5；大城市建议 10/20）
                  colony-manager-tick-cache-enabled: true  # 殖民地 ServerTick 事件 getAllColonies 快照缓存（A/B：minecolonies 主线程自耗时 -60%）
                  colony-manager-tick-cache-interval: 20   # 快照 TTL tick（1-120；create/delete 立即失效）
                  chunk-demand-per-tick: 50           # 主线程每 tick 处理的 chunk 需求上限（统一需求调度）
                  chunk-demand-player-priority: false # chunk 需求玩家距离优先级：按提交时距最近玩家分 4 桶优先消费（默认关）
                  chunk-demand-starve-ticks: 600      # 低优先级桶队头超龄即优先消费（饿死兜底，仅 priority 开启时生效）
                  region-count: 4                    # 区域数（2/4/8/16；16 时条纹宽自动扩到 16）
                  region-auto-scale: true            # 按负载自动调整区域数
                  region-scale-interval-seconds: 300
                  region-scale-high-mspt: 60.0
                  region-scale-low-mspt: 15.0
                  region-scale-stable-periods: 2
                  region-scale-min: 2
                  region-scale-max: 8
                  region-scale-cross-read-ratio: 0.05
                  uneven-stripes: false              # S4 不等宽条带：高负载区让出边界组给低负载相邻区（默认关）
                  rebalance-interval-seconds: 300    # 重平衡评估周期（与 auto-scale 同窗）
                  rebalance-max-moves: 1             # 单轮最多移动边界组数（v1 恒 1）
                  rebalance-min-groups: 1            # 移动后每区最少组数（N≥8 时区宽=1 组自动跳过）
                  rebalance-imbalance-ratio: 2.0     # norm(H) > norm(L)*ratio 才重平衡（归一化口径，防抖）
                  thread-policy: stats             # worker 世界访问策略: off/stats/enforce（生产用 stats）
                  violation-log-per-minute: 20     # 违规日志每分钟每类限流条数
                  main-thread-routing: auto        # auto 违规学习 / manual 只认种子列表
                  route-threshold: 5               # 窗口内 MAIN_ONLY 违规次数即路由主线程（0=禁用学习；灰度后从 2 调宽）
                  route-window-ticks: 2400         # 违规学习窗口（tick，2400=2分钟）
                  route-on-read: true              # MAIN_ONLY_READ 是否计入路由（worker 读 BE 恒 null；false=只按写路由）
                  crossref-probe: false              # 跨区引用探针：采样 worker 方块实体访问并分桶（默认关）
                  crossref-value-snapshot: false      # 值快照：探针命中容器类时读时复制做影子验证（默认关）
                  belt-passenger-defer: false      # worker 实体撞传送带时 passenger 注册延迟到主线程（配合 route-on-read=false）
                  main-thread-entity-force: []     # 强制主线程 tick 的类名/前缀
                  main-thread-entity-allow: []     # 强制不路由的类名/前缀（危险调试用）
                  persist-learned-routes: false    # 停机时把学到的路由写回配置（仅实体类，最多 200 条）
                  journal-max-per-region: 4096     # 跨区写 journal 每区域队列上限（最旧丢弃）
                  journal-read-back: false         # read-your-writes overlay（预留接口，默认关）
                  determinism-mode: false           # 确定性模式：跨区 journal 按区域序在调度线程统一应用（默认关）
                  be-parallel-allow: []            # BE 三档：允许 region worker tick 的类型（registry key 或前缀*）
                  be-main-thread-force: ["create:track", "lootr:lootr_chest"] # BE 三档：强制主线程类型（尖峰/跨区依赖）
                  create-track-lazy-spread: false   # Create 长轨道假轨光栅化分摊（默认关）
                  create-track-lazy-chunk-blocks: 64 # 分摊时单连接每 tick 最多栅格块数
                  villager-poi-path-budget: 0       # 村民主线程 POI/单目标寻路预算（0=关闭）

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
                  threaded: false                    # 独立光照线程：光邮箱+任务排序器迁出共享后台池（默认关；开启后每维度一个守护线程）

                # 实体空间索引：EntitySection 内懒 4×4×4 子格索引（默认开）
                # 加速纯空间 AABB 查询（getEntities(AABB)）与 typed 查询（getEntitiesOfClass 等，
                # entityspatial 二期：在 vanilla 类列表上按覆盖格子预筛，结果/顺序与原版逐位一致）；
                # 返回顺序与原版一致；对 Lithium/Canary/Radium/Recruits 让位。
                # 2026-08-16 真机 A/B：120 只僵尸高密度场景 avg mspt 5.1→3.4ms（-33%）。
                entity-spatial-index:
                  enabled: true                      # 默认开（可随时关；异常时看 [entity-spatial-index] 日志）
                  min-section-size: 16               # section 实体数达到该值才建索引（小 section 走原版线性扫描）
                  telemetry-enabled: true            # 采集查询/候选数进 [entity-spatial-index] 日志

                # POI 查询加速：PoiManager.getInChunk 空 chunk 存在性预检（默认开）
                # 1.21.1 的 PoiSection 已按 PoiType 分桶（vanilla 自带），剩余成本 = 查询范围内
                # 大量无 POI 区块的全垂直 section 扫描；本优化维护「区块是否有 POI」位掩码，
                # 已知空区块直接跳过，只迭代有 POI section 的 y 层。语义零变化；冷区块
                # （未读盘）保持原版 getOrLoad 路径（含同步读盘），磁盘 POI 不会漏。
                poi-query:
                  enabled: true                      # 默认开（纯读加速，语义零变化）
                  telemetry-enabled: true            # 命中/跳过计数进 [poi-query] 日志

                # 碰撞批量收集：Entity.collide 上台阶分支二次收集去重（默认开）
                # 1.21.1 collideBoundingBox 已「一次收集、逐轴 clip」；但 step-up 上台阶分支会对
                # 扩展区域再次全量 collectColliders（走路生物每次地面移动都付）。本优化在同一
                # collide 帧内缓存首次收集结果，step-up 只增量补取顶部条带。语义零变化
                # （补集合并 = 全量结果）；对 Lithium/Canary/Radium 让位。
                collision-batch:
                  enabled: true                      # 默认开（纯读加速，语义零变化）
                  telemetry-enabled: true            # 命中/增量/全量计数进 [collision-batch] 日志

                # 容器菜单广播预检短路（默认关，P3「先实测归因」）
                # 1.21.1 的 broadcastChanges 每 tick 对每个打开菜单全量遍历全部槽位，每槽
                # 做 getItem + requireNonNull + memoize lambda 分配（即使菜单长期静止）。
                # 本优化在 HEAD 用与原版逐条等价的判定（lastSlots diff / remoteCarried diff /
                # dataSlots 值快照）预检：全部相等 = 原版循环必然无动作，直接跳过整个循环，
                # 省掉全部 lambda 分配与重复 diff。语义逐位一致：不是脏槽跟踪，是全量 diff
                # 的提前等价物，mod 直写容器（Container.setItem 绕过 menu）同样被捕获。
                # 注意：默认关——先用生产服 spark 看 broadcastChanges 子树占比再决定开启。
                menu-broadcast:
                  enabled: false                     # 默认关（实测归因后再开）
                  telemetry-enabled: true            # 短路/全量/槽位检查数进 [menu-broadcast] 日志

                # 事件桥按需注册（P0-1，默认开）+ 空监听器预检（P0-2）
                # Arclight 的 5 个 Forge 桥 dispatcher 从「启动时无条件注册」改为按
                # 「有插件在听对应 Bukkit 事件」按需注册/注销（0→1 注册、1→0 注销）。
                # 无插件监听的服务器上 Forge 事件照发、mod 监听器照收，只是桥自己的
                # 监听器不在总线上——桥开销（CraftBlock/事件构造 + 空派发 + 回写）归零。
                # 事件数量与时机零变化（只动 Arclight 自己的监听器，不动事件本身）。
                # 生产服第一大热点 EventBus.post 子树（实测 75.5%）中 Arclight 桥的份额
                # 由本优化消除；mod 监听器主体与事件构造（vanilla/NeoForge 调用侧）不在此列。
                event-bridge:
                  on-demand-registration:
                    enabled: true                     # 桥监听器按需注册（默认开）
                    eager-registration: false         # 恢复 mod 加载期常驻注册（顺序敏感场景逃生门）
                    telemetry-enabled: true           # 转发/跳过/注册注销计数进 [event-bridge] 日志

                # 事件短路（P1-3/P1-4，默认开，零语义风险）
                # EntityTickEvent：每实体每 tick ×2（频率之王）；无监听器时 Pre 恒未取消
                # = entity.tick() 照跑，短路逐位等价。NeighborNotifyEvent：NeoForge 在
                # vanilla 空壳 updateNeighborsAt 上 fire 事件且丢弃 isCanceled 结果——
                # 无监听器时连 fire 都可跳过。两者有监听器时自动让位（length>0 判断）。
                event-shortcircuit:
                  entity-tick-event:
                    enabled: true                     # 无监听器时跳过 EntityTickEvent 构造与 post
                  neighbor-notify-event:
                    enabled: true                     # 无监听器时跳过 NeighborNotifyEvent 构造与 post
                  block-form-event:
                    enabled: true                     # P1-2 直接调用点：无监听器时跳过 BlockFormEvent/EntityBlockFormEvent 构造与派发（callBlockFormEvent 漏斗，8 个调用点统一覆盖）
                  mob-spawn-event:
                    enabled: true                     # P2-3 无监听器时跳过 MobSpawnEvent.PositionCheck / MobDespawnEvent 构造与派发（内联原版判定结果，语义逐位等价）
                  telemetry-enabled: true             # 短路/转发计数进 [event-shortcircuit] 日志
                """;
        try {
            Files.writeString(file.toPath(), template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write default prts-features.yml", e);
        }
    }
}
