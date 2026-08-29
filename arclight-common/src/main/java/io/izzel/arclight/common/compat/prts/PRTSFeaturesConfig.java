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
    /** 主线程 routed entity drain 每 tick 处理上限（0 = 不分批，全量 drain）。 */
    public static int mainThreadEntityDrainBudget;
    /** 主线程单目标移动寻路异步化（routed villager 主线程 A* 削峰；默认关）。 */
    public static boolean mainThreadPathAsync;
    /** learned routes JSON 文件路径。 */
    public static String learnedRoutesFile;
    /** 最多持久化的 learned routes 数量。 */
    public static int learnedRoutesLimit;
    /** 启用双向 probation（自动路由类定期试跑 worker，无违规则解除路由）。 */
    public static boolean routeProbationEnabled;
    /** probation 间隔（tick）。 */
    public static int routeProbationTicks;
    /** 允许 probation 的最大历史违规数（超过此值的类不做 probation）。 */
    public static int routeProbationMaxViolations;
    /** 殖民地管理器 ServerTick 事件里的 getAllColonies 快照缓存（默认关；测试服 A/B 后定默认）。 */
    public static boolean colonyManagerTickCacheEnabled;
    /** 殖民地列表快照的 TTL（tick）；过期自动重建，create/delete 路径立即失效。 */
    public static int colonyManagerTickCacheInterval;
    /** EventBus 事件分发遥测（诊断用，默认关；开启后会给每个事件类挂 HIGHEST/LOWEST 计时监听器）。 */
    public static boolean eventBusTelemetryEnabled;
    /** 主线程每 tick 处理的 chunk 需求上限（统一需求调度，默认 50）。 */
    public static int chunkDemandPerTick;
    /** 主线程 chunk 需求排空最低保证窗口（ms，默认 2；0=关闭）：低 TPS 超预算仍至少排空，防死亡螺旋。 */
    public static int chunkDemandMinDrainMs;
    /** chunk 需求玩家距离优先级（分 4 桶优先消费，默认关）。 */
    public static boolean chunkDemandPlayerPriority;
    /** 低优先级桶队头超龄即优先消费的阈值（tick，饿死兜底）。 */
    public static int chunkDemandStarveTicks;
    /** 玩家方向区块预取（默认关）：按移动方向对视距外投临时 ticket（到期自动回收）。 */
    public static boolean chunkPrefetchEnabled;
    /** 进服热点预热：启动后把配置中心区域加载到 FULL（玩家首登免磁盘等待）。 */
    public static boolean loginWarmupEnabled;
    public static int loginWarmupRadius;
    public static int loginWarmupPerTick;
    public static int[][] loginWarmupCenters;
    /** 视距外预取深度（格）。 */
    public static int chunkPrefetchDepth;
    /** 每玩家预取重算间隔（tick）。 */
    public static int chunkPrefetchIntervalTicks;
    /** 预取 ticket 存活时长（tick，到期自动回收）。 */
    public static int chunkPrefetchTimeoutTicks;
    /** 预铺窗口深度（区块；depth 已废弃）。 */
    public static int chunkPrefetchWindow;
    /** 预铺走廊宽度（区块；窄走廊控管线负载，防饱和）。 */
    public static int chunkPrefetchWindowWidth;
    /** 窗口重算触发：跨块 ≥ windowStep。 */
    public static int chunkPrefetchWindowStep;
    /** 窗口重算触发：距上次重算 ≥ windowRecomputeTicks。 */
    public static int chunkPrefetchWindowRecomputeTicks;
    /** 预铺任务优先级档（56-63，需求档 clamp 到其下一档）。 */
    public static int chunkPrefetchPriority;
    /** 窗口内未完成预铺任务上限（窗口重算时补货，最近优先）。 */
    public static int chunkPrefetchMaxPending;
    /** idle 背景预生成（玩家静止时低优先级铺根，默认开）。 */
    public static boolean chunkPrefetchIdleEnabled;
    /** idle 预生成半径（区块）。 */
    public static int chunkPrefetchIdleRadius;
    /** idle 每 tick 预铺上限（平均速率）。 */
    public static int chunkPrefetchIdlePerTick;
    /** 进入 idle：连续 ticks 位移 < 2 块。 */
    public static int chunkPrefetchIdleEnterTicks;
    /** 退出 idle 阈值（保留兼容；现按任意跨块退出）。 */
    public static int chunkPrefetchIdleExitBlocks;
    /** processQueue 按需唤醒（marshal 请求在 tick 间及时服务；纯延迟优化，默认开）。 */
    public static boolean processQueueWake;
    /** 整服熔断（默认关）：连续 3 次 barrier 硬超时后,整个并行引擎退原版串行(重启恢复)。 */
    public static boolean onFaultFallbackVanilla;
    /** 生物间碰撞开关（默认关）：关闭后生物不阻挡、可重叠穿行；玩家碰撞保留。 */
    public static boolean mobCollisionEnabled;
    /** 玩家是否也参与碰撞关闭（谨慎选项，默认关）。 */
    public static boolean mobCollisionPlayersAffected;
    /** 区块环境 tick(随机/流体)并行（默认关）：主线程 tickChunks 扇出到独立子任务池。 */
    public static boolean chunkEnvParallel;
    /** 区块环境并行子任务池大小（0=auto=CPU）。 */
    public static int chunkEnvThreads;
    /** 区块环境并行 3×3 区块锁（默认开，互斥相邻 chunk 并发写）。 */
    public static boolean chunkEnvLock;
    /** 实体批并行（默认关）：region worker 实体阶段内扇出到独立子任务池。 */
    public static boolean entityBatchParallel;
    /** 实体批并行子任务池大小（0=auto=max(2, CPU-region_count)）。 */
    public static int entityBatchThreads;
    /** 实体批并行白名单（显式放行 modded 类，registry key 或前缀*）。 */
    public static List<String> entityBatchAllow = new ArrayList<>();
    /** 实体批并行黑名单（强制排除，优先级高于白名单）。 */
    public static List<String> entityBatchDeny = new ArrayList<>();
    /** 异步传送门（默认关）：worker 上目标维度区块未 FULL 时提交异步加载并延后一 tick。 */
    public static boolean portalAsync;
    /** 动态区域自动扩容：按负载周期性地调整区域数。 */
    public static boolean regionAutoScale;
    public static long regionScaleIntervalSeconds;
    public static double regionScaleHighMspt;
    public static double regionScaleLowMspt;
    public static int regionScaleStablePeriods;
    public static int regionScaleMin;
    public static int regionScaleMax;
    public static double regionScaleCrossReadRatio;
    /** 不等宽条带：组内边界重平衡总开关（默认关）。 */
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
    /** 影子快照单条缓存（证伪实验，默认关）：需同时开 crossref-value-snapshot 才有影子流量，否则无效果。 */
    public static boolean crossrefSnapshotCache;
    /** B4: worker 上实体撞 Create 传送带时把 passenger 注册延迟到主线程执行，
     *  使 villager 等回并行的实体仍能被传送带运输。默认 false（配合 route-on-read=false 使用）。 */
    public static boolean beltPassengerDefer;
    /** 强制主线程 tick 的类名/前缀（优先级最高）。 */
    public static List<String> mainThreadEntityForce;
    /** 强制不路由的类名/前缀（危险调试用，覆盖学习与种子）。 */
    public static List<String> mainThreadEntityAllow;
    /** SBW 空车休眠（S2.11 §2.16，默认关）：静止无乘客的非残骸 SBW 载具在调度层
     *  降为心跳 tick（本 tick 跳过 dispatch）。只作用于 SBW 载具，其它模组实体零影响。 */
    public static boolean sbwVehicleSleep;
    /** 空车休眠心跳间隔（tick，默认 10 = 0.5 秒一跳，被推/上车/受击经心跳轮询恢复）。 */
    public static int sbwVehicleSleepInterval;
    /** 优雅停机时把本会话学到的路由追加写回配置文件（默认关，后续版本启用）。 */
    public static boolean persistLearnedRoutes;
    /** 跨区写 journal 每区域队列上限（最旧条目丢弃，默认 4096）。 */
    public static int journalMaxPerRegion;
    /** 跨区写 journal LWW 去重——同 pos 未应用条目合并为最新一条（默认开；关=逐条排队旧行为）。 */
    public static boolean journalLwwDedup;
    /** 跨区写 journal 每维度每 tick 提交上限（超出丢弃计 budgetDropped，下 tick 调用方自然重提；0=不限，默认 512）。 */
    public static int journalMaxPerTick;
    /** 串行回退路径（红石网络优化 mod 在位）的方块 tick：在维度 worker 上执行时延迟到主线程 POST（默认开；关=回到 inline worker 旧行为）。 */
    public static boolean blockTickMainThreadWhenSerialized;
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

    // 堆压力卫兵：committed/max 超阈值时压缩生成提交预算，让 GC 追回内存
    //（登录/预热 chunk 加载风暴把 committed 顶到 Xmx 导致 RSS 超面板限制被杀）。
    public static boolean generationMemoryGuardEnabled;
    public static double generationMemoryGuardThrottleRatio;
    public static double generationMemoryGuardPauseRatio;

    // 区域 worker 每阶段时间片（ms）：实体/方块刻/BE 循环到点让出，剩余丢弃
    // 计 droppedWork（下 tick dispatch 重新入队，与原版省略 tick 同谱系）。
    public static long regionWorkerSliceMs;

    // 维度 worker 每 tick 方块/流体计划 tick 预算（0 = vanilla 65536）。岩浆扩散
    // backlog 会让 worker 单 tick 磨数分钟，主线程 barrier 干等被用户视为卡死。
    public static int workerTickBudget;

    // 无玩家维度每 barrier 会话最多 tick 数（1 = 单 tick）：多 tick 批量消化
    // backlog，维度时钟跑快 N 倍（机器加速）；有玩家维度恒 1，防世界速度漂移。
    public static int dimensionWorkerMultitick;

    // 多 tick 会话墙钟上限（ms）：到点停（当前 tick 完成即止），防 barrier 超时。
    public static long dimensionWorkerSessionMs;

    // 主线程 POST 各 drain 的时间预算（ms）：岩浆机背压大时延迟队列积压数万条，
    // 无界清空会把主线程卡死数十秒；到点即停，剩余顺延下一会话。
    public static long postDrainBudgetMs;

    // Barrier semantics: make the vanilla watchdog barrier-aware so a main thread waiting
    // in the dimension barrier is not falsely killed after max-tick-time; false = vanilla
    // watchdog behavior.
    public static boolean barrierWatchdogAware;
    /** Barrier await timeout ms; on expiry dump all threads and crash with a report. */
    public static long barrierTimeoutMs;
    /**
     * 每次 {@code drainScheduleTasks} 的时间预算（毫秒）。风暴期新生块大量灌入计划
     * tick（液体扩散回环）时，无界清空会卡死维度 tick 线程直到屏障超时崩溃；
     * 超限部分顺延下一 tick 的 drain（ScheduledTick 的绝对触发时刻不变，最多少数
     * tick 晚落地几 tick，与原版高负载下的延迟行为同向）。
     */
    public static long scheduleDrainBudgetMs;
    // B 组 barrier 软降级(时间切片 join, docs/2026-08-27-region-parallel-barrier-idle-fix.md §2)。
    // 主线程该 tick 已超预算(落后)时,把 barrier 等待从 barrierTimeoutMs 改为本 tick 剩余预算
    // (target - elapsed,下限 10ms),超时的 region 本轮降级:其未达成的 work 取出即丢弃
    // (计 barrier.droppedWork),存活实体下一 tick 由 dispatch 重新入队恰好 tick 一次。
    // 默认关(§7 逐个开):开启会改变低 TPS 时的 tick 完整性,必须靠 droppedRegion 遥测对比后再留用。
    public static boolean barrierSoftDegrade;
    /** B:整 tick 目标预算 ms;elapsed > 该值才激活软降级(0 = 永不激活)。 */
    public static int barrierTargetMs;
    /** §1.1:量化 barrier 等待与 join 后主线程工作(players/localTicks)的重叠空间(纯遥测,默认关)。 */
    public static boolean mainWastedMsTelemetry;
    // 硬超时行为(barrierTimeoutMs 超时后):degrade = ERROR+dump + 该维度转主线程串行 + 自动恢复;crash = 崩服。
    public enum BarrierTimeoutAction { CRASH, DEGRADE }
    /** 硬超时后的行为(默认 degrade)。 */
    public static BarrierTimeoutAction barrierTimeoutAction = BarrierTimeoutAction.DEGRADE;
    /** degraded 状态自动恢复并行所需的连续正常 tick 数。 */
    public static int barrierTimeoutRecoverTicks = 6000;

    // Lighting - per-tick light propagation budget + telemetry (PRTS 光照预算化).
    // 限制每 tick 光照传播工作量，风暴时超出部分顺延下一 tick（最终光照一致，只是延迟）。
    public static boolean lightBudgetEnabled;
    /** 每 tick 最多传播的方块数（0 = 不限/vanilla）。 */
    public static int lightBudgetPerTick;
    /** 采集光照队列长度/耗时进 AsyncTaskStats（[light-engine] 日志）。 */
    public static boolean lightTelemetryEnabled;
    /** 独立光照线程——light 邮箱 + 任务排序器迁出共享后台池，
     *  隔离光照传播与 worldgen 线程池争抢（每维度一个守护线程，默认关）。 */
    public static boolean lightThreadEnabled;

    /** 区块系统调度器（M1：FlowSched 精简移植驱动原版生成 future 链，默认关；启动期生效，改值需重启）。 */
    public static boolean chunkSystemSchedulerEnabled;
    /** 调度器 worker 线程数（仅调度层；M1 默认 1 保持原版串行语义，M2 起随状态机重写放开）。 */
    public static int chunkSystemSchedulerWorkers;
    /** 调度器锁域半径（区块数；0=仅中心块，1=3×3，2=5×5）。feature 阶段存在跨区块写，
     *  实测写半径达 2（= ChunkStatus.FULL 累计生成半径），半径 &lt; 2 会触发并发写/死锁。 */
    public static int chunkSystemSchedulerLockRadius;
    /** 两阶段锁域拆分（阶段二）：features 前步骤只锁中心块（原版声明写半径均为 0，
     *  邻块读由 future 依赖链保序），进入 FEATURES 层前一次性暂停换 5×5 锁续跑。
     *  目的：把锁串行限制在 features 段，恢复前段并行度。默认关，测服灰度。 */
    public static boolean chunkSystemSchedulerSplitStages;
    /** 依赖门控（阶段四）：挂起时收集当前层全部未完成 future，全完成后才重新入队，
     *  消除原版「尾 future 完成即唤醒→层内未就绪立即再挂起」的空转往返。默认关，测服灰度。 */
    public static boolean chunkSystemSchedulerDepGating;
    /** 区块系统状态机（M2.1）：细粒度「单区块×单状态」任务图替代 M1 的整任务驱动。
     *  任务依赖边运行时直接读 ChunkPyramid 各步 directDependencies（与 WorldGenRegion
     *  读合法性检查同表），锁半径按 M2.0-2 审计表（FEATURES ±2 / STRUCTURE_STARTS ±1 /
     *  其余中心块）。优先级高于 chunk-system-scheduler.enabled（两者同开时本项生效）。
     *  启动期生效（早于 createLevels），改值需重启；默认关。 */
    public static boolean chunkSystemEnabled;
    /** 主线程边界 fail-fast 守卫（M2.2 四件套 ①）：ServerChunkCache.tick/save 等维度级事务
     *  非属主线程（服务器主线程 ∪ 维度事件循环 ∪ region worker）调用即抛异常定位违约链。
     *  仅在 chunk-system-enabled 下生效；默认开。 */
    public static boolean chunkSystemFailFastGuards;
    /** IO 反序列化移出主线程（M2.2 IO 线程模型）：ChunkSerializer.read 段从
     *  thenApplyAsync(mainThreadExecutor) 改投调度器最低优先级档，配套事件捕获延迟队列。
     *  仅在 chunk-system-enabled 下生效；启动期语义（读盘路径），默认开。 */
    public static boolean chunkAsyncIoEnabled;
    /** 反序列化线程数（1=串行保持原语义；>1 并行构建区块对象图，POI 段由 PoiManager 锁串行化）。 */
    public static int chunkDeserializeThreads;
    /** World.random 跨线程检测模式（M2.2 四件套 ④）：warn=限流日志+回退（默认）、
     *  throw=抛异常、其他=不装装饰器。仅在 chunk-system-enabled 下生效。 */
    public static String worldgenRandomCheck;

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

    // Menu broadcast precheck - AbstractContainerMenu.broadcastChanges 全等预检短路（默认关）。
    // 1.21.1 的 broadcastChanges 每 tick 对每个打开菜单全量遍历所有槽位：每槽 getItem +
    // requireNonNull + memoize lambda 分配，随后 triggerSlotListeners/synchronizeSlotToRemote
    // 内部都做 lastSlots diff（无变化时纯浪费，但 lambda 已分配）。本优化在 HEAD 用与原版
    // 逐条等价的判定（lastSlots/remoteCarried/dataSlots 值缓存）预检：全部相等 = 原版循环
    // 必然无动作，直接跳过整个循环。语义逐位一致（预检不是脏槽跟踪，是全量 diff 的提前
    // 等价物——mod 直写容器同样被同一 diff 捕获，零漏检）。默认关：定位「先实测归因」，
    // 生产服 spark 确认 broadcastChanges 子树占比后再开。
    public static boolean menuBroadcastEnabled;
    /** 采集短路/全量/槽位检查数进 AsyncTaskStats（[menu-broadcast] 日志）。 */
    public static boolean menuBroadcastTelemetryEnabled;

    // Event bridge on-demand registration.
    // Arclight 的 5 个 Forge 桥 dispatcher 从「启动时无条件注册」改为按「有插件在听对应
    // Bukkit 事件」按需注册/注销（SimplePluginManager 注册/注销路径挂钩，0→1 注册、1→0
    // 注销）。无插件监听的服务器上 Forge 事件照发、mod 监听器照收，只是桥自己的监听器
    // 不在总线上——桥开销（CraftBlock/事件构造 + 空派发 + 回写）整块归零。
    // 防御层：dispatcher 入口 O(1) 空监听器预检（HandlerList 空则跳过构造+派发）。
    // 兼容红线：事件数量与时机零变化（只动 Arclight 自己的监听器是否在总线）。
    public static boolean eventBridgeOnDemandEnabled;
    /** 恢复 mod 加载期常驻注册（顺序敏感场景的逃生门）。 */
    public static boolean eventBridgeEagerRegistration;
    /** 采集转发/跳过/注册注销计数进 [event-bridge] 日志。 */
    public static boolean eventBridgeTelemetryEnabled;

    // Event short-circuit.
    // EntityTickEvent（每实体每 tick ×2，频率之王）与 NeighborNotifyEvent（结果被丢弃）
    // 在无监听器时短路掉事件构造与 post——零语义风险（无监听器 = Pre 恒未取消 = tick 照跑；
    // onNeighborNotify 的 isCanceled 结果原代码直接丢弃）。
    public static boolean eventShortcircuitEntityTickEnabled;
    public static boolean eventShortcircuitNeighborNotifyEnabled;
    /** 采集短路/转发计数进 [event-shortcircuit] 日志。 */
    public static boolean eventShortcircuitTelemetryEnabled;
    /** 直接调用点短路：callBlockFormEvent（BlockFormEvent/EntityBlockFormEvent）无监听器时返回 null。 */
    public static boolean eventShortcircuitBlockFormEnabled;
    /** 刷怪事件短路：MobSpawnEvent.PositionCheck / MobDespawnEvent 无监听器时跳过构造与派发（内联原版判定结果）。 */
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
        chunkDemandMinDrainMs = Math.max(0, config.getInt("parallel.chunk-demand-min-drain-ms", 2));
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.minDrainMs = chunkDemandMinDrainMs;
        chunkDemandPlayerPriority = config.getBoolean("parallel.chunk-demand-player-priority", false);
        chunkDemandStarveTicks = Math.max(20, config.getInt("parallel.chunk-demand-starve-ticks", 600));
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.playerPriorityEnabled = chunkDemandPlayerPriority;
        io.izzel.arclight.common.optimization.general.servercore.ChunkDemandQueue.starveNanos = chunkDemandStarveTicks * 50_000_000L;
        LOGGER.info("parallel chunk-demand priority={} starve={} ticks", chunkDemandPlayerPriority, chunkDemandStarveTicks);
        // 玩家方向区块预取（默认关）。
        chunkPrefetchEnabled = config.getBoolean("chunk-prefetch.enabled", false);
        chunkPrefetchDepth = Math.max(1, config.getInt("chunk-prefetch.depth", 6));
        chunkPrefetchIntervalTicks = Math.max(1, config.getInt("chunk-prefetch.interval-ticks", 5));
        chunkPrefetchTimeoutTicks = Math.max(20, config.getInt("chunk-prefetch.timeout-ticks", 200));
        // 窗口预铺（depth 已废弃，保留解析兼容）。
        chunkPrefetchWindow = Math.max(4, config.getInt("chunk-prefetch.window", 16));
        chunkPrefetchWindowWidth = Math.max(3, config.getInt("chunk-prefetch.window-width", 5));
        chunkPrefetchWindowStep = Math.max(1, config.getInt("chunk-prefetch.window-step", 2));
        chunkPrefetchWindowRecomputeTicks = Math.max(10, config.getInt("chunk-prefetch.window-recompute-ticks", 40));
        chunkPrefetchPriority = Math.min(63, Math.max(56, config.getInt("chunk-prefetch.prefetch-priority", 56)));
        chunkPrefetchMaxPending = Math.max(16, config.getInt("chunk-prefetch.max-pending", 512));
        chunkPrefetchIdleEnabled = config.getBoolean("chunk-prefetch.idle-enabled", true);
        chunkPrefetchIdleRadius = Math.max(1, config.getInt("chunk-prefetch.idle-radius", 8));
        chunkPrefetchIdlePerTick = Math.max(1, config.getInt("chunk-prefetch.idle-per-tick", 16));
        chunkPrefetchIdleEnterTicks = Math.max(20, config.getInt("chunk-prefetch.idle-enter-ticks", 100));
        chunkPrefetchIdleExitBlocks = Math.max(2, config.getInt("chunk-prefetch.idle-exit-blocks", 4));
        LOGGER.info("chunk-prefetch enabled={} window={}x{} step={} recompute={} priority={} maxPending={} idle(enabled={} radius={} perTick={} enter={} exit={}) timeout={}",
                chunkPrefetchEnabled, chunkPrefetchWindow, chunkPrefetchWindowWidth, chunkPrefetchWindowStep,
                chunkPrefetchWindowRecomputeTicks, chunkPrefetchPriority, chunkPrefetchMaxPending,
                chunkPrefetchIdleEnabled, chunkPrefetchIdleRadius, chunkPrefetchIdlePerTick,
                chunkPrefetchIdleEnterTicks, chunkPrefetchIdleExitBlocks, chunkPrefetchTimeoutTicks);
        processQueueWake = config.getBoolean("parallel.process-queue-wake", true);
        loginWarmupEnabled = config.getBoolean("parallel.login-warmup-enabled", true);
        loginWarmupRadius = Math.max(0, config.getInt("parallel.login-warmup-radius", 32));
        loginWarmupPerTick = Math.max(1, config.getInt("parallel.login-warmup-per-tick", 32));
        loginWarmupCenters = parseNestedPairs(config.getString("parallel.login-warmup-centers",
                "[[472,1358],[2113,1925],[-629,-278],[-672,256],[1242,2292]]"));
        onFaultFallbackVanilla = config.getBoolean("parallel.on-fault-fallback-vanilla", false);
        mobCollisionEnabled = config.getBoolean("mob-collision.enabled", false);
        mobCollisionPlayersAffected = config.getBoolean("mob-collision.players-affected", false);
        chunkEnvParallel = config.getBoolean("parallel.chunk-env-parallel", false);
        chunkEnvThreads = Math.max(0, config.getInt("parallel.chunk-env-threads", 0));
        chunkEnvLock = config.getBoolean("parallel.chunk-env-lock", true);
        portalAsync = config.getBoolean("parallel.portal-async", false);
        entityBatchParallel = config.getBoolean("parallel.entity-batch-parallel", false);
        entityBatchThreads = Math.max(0, config.getInt("parallel.entity-batch-threads", 0));
        entityBatchAllow = parseInlineList(config.getString("parallel.entity-batch-allow", "[]"));
        entityBatchDeny = parseInlineList(config.getString("parallel.entity-batch-deny", "[]"));
        io.izzel.arclight.common.optimization.general.servercore.EntityBatchScheduler
                .configure(entityBatchAllow, entityBatchDeny);
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
        sbwVehicleSleep = config.getBoolean("parallel.sbw-vehicle-sleep", false);
        sbwVehicleSleepInterval = Math.max(2, Math.min(120, config.getInt("parallel.sbw-vehicle-sleep-interval", 10)));
        LOGGER.info("parallel sbw-vehicle-sleep={} interval={}", sbwVehicleSleep, sbwVehicleSleepInterval);
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", false);
        ClassAffinityLedger.applyConfig(routeThreshold, routeWindowTicks, routeOnRead);
        crossrefProbe = config.getBoolean("parallel.crossref-probe", false);
        crossrefValueSnapshot = config.getBoolean("parallel.crossref-value-snapshot", false);
        crossrefSnapshotCache = config.getBoolean("parallel.crossref-snapshot-cache", false);
        io.izzel.arclight.common.optimization.general.servercore.ownership.CrossRefProbe.applyConfig(crossrefProbe, crossrefValueSnapshot, crossrefSnapshotCache);
        LOGGER.info("parallel crossref-probe={} value-snapshot={} snapshot-cache={}", crossrefProbe, crossrefValueSnapshot, crossrefSnapshotCache);
        LOGGER.info("parallel main-thread-routing={} threshold={} window={} ticks force={} allow={} persist={}",
                mainThreadRouting, routeThreshold, routeWindowTicks,
                mainThreadEntityForce.size(), mainThreadEntityAllow.size(), persistLearnedRoutes);
        journalMaxPerRegion = Math.max(16, config.getInt("parallel.journal-max-per-region", 4096));
        journalLwwDedup = config.getBoolean("parallel.journal-lww-dedup", true);
        journalMaxPerTick = Math.max(0, config.getInt("parallel.journal-max-per-tick", 512));
        LOGGER.info("parallel journal lww-dedup={} max-per-tick={} max-per-region={}",
                journalLwwDedup, journalMaxPerTick, journalMaxPerRegion);
        blockTickMainThreadWhenSerialized = config.getBoolean("parallel.block-tick-main-thread-when-serialized", true);
        LOGGER.info("parallel block-tick-main-thread-when-serialized={}", blockTickMainThreadWhenSerialized);
        journalReadBack = config.getBoolean("parallel.journal-read-back", false);
        determinismMode = config.getBoolean("parallel.determinism-mode", false);
        beParallelAllow = new ArrayList<>(config.getStringList("parallel.be-parallel-allow"));
        beMainThreadForce = new ArrayList<>(config.getStringList("parallel.be-main-thread-force"));
        if (beMainThreadForce.isEmpty()) {
            // 实测：create:track 单次 tick 最大 342ms（列车图全局计算），铁主线程。
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
        generationMemoryGuardEnabled = config.getBoolean("generation-memory-guard-enabled", true);
        generationMemoryGuardThrottleRatio = Math.min(0.95, Math.max(0.3,
                config.getDouble("generation-memory-guard-throttle-ratio", 0.65)));
        generationMemoryGuardPauseRatio = Math.min(0.99, Math.max(0.4,
                config.getDouble("generation-memory-guard-pause-ratio", 0.85)));
        workerTickBudget = config.getInt("parallel.worker-tick-budget", 4096);
        if (workerTickBudget < 0) workerTickBudget = 0;
        dimensionWorkerMultitick = Math.max(1, config.getInt("parallel.dimension-worker-multitick", 4));
        dimensionWorkerSessionMs = Math.max(500, config.getLong("parallel.dimension-worker-session-ms", 8000L));
        postDrainBudgetMs = Math.max(50, config.getLong("parallel.post-drain-budget-ms", 1000L));
        regionWorkerSliceMs = Math.max(50, config.getLong("parallel.region-worker-slice-ms", 2000L));
        barrierWatchdogAware = config.getBoolean("barrier-watchdog-aware", true);
        barrierTimeoutMs = config.getLong("barrier-timeout-ms", 120000L);
        if (barrierTimeoutMs < 1000L) barrierTimeoutMs = 120000L;
        scheduleDrainBudgetMs = config.getLong("schedule-drain-budget-ms", 8L);
        if (scheduleDrainBudgetMs < 1L) scheduleDrainBudgetMs = 8L;
        // B 组:barrier 软降级(时间切片 join)。默认关——改动 tick 完整性语义,须遥测对比后逐个开。
        barrierSoftDegrade = config.getBoolean("parallel.barrier-soft-degrade", false);
        barrierTargetMs = Math.max(0, config.getInt("parallel.barrier-target-ms", 50));
        mainWastedMsTelemetry = config.getBoolean("parallel.main-wasted-ms-telemetry", false);
        LOGGER.info("parallel barrier-soft-degrade={} target-ms={} main-wasted-ms-telemetry={}",
                barrierSoftDegrade, barrierTargetMs, mainWastedMsTelemetry);
        String barrierTimeoutActionRaw = config.getString("parallel.barrier-timeout-action", "degrade");
        barrierTimeoutAction = "crash".equalsIgnoreCase(barrierTimeoutActionRaw)
                ? BarrierTimeoutAction.CRASH : BarrierTimeoutAction.DEGRADE;
        barrierTimeoutRecoverTicks = Math.max(1, config.getInt("parallel.barrier-timeout-recover-ticks", 6000));
        LOGGER.info("parallel barrier-timeout-action={} recover-ticks={}",
                barrierTimeoutAction, barrierTimeoutRecoverTicks);
        lightBudgetEnabled = config.getBoolean("lighting.budget-enabled", true);
        lightBudgetPerTick = config.getInt("lighting.budget-per-tick", 100000);
        if (lightBudgetPerTick < 0) lightBudgetPerTick = 0;
        lightTelemetryEnabled = config.getBoolean("lighting.telemetry-enabled", true);
        lightThreadEnabled = config.getBoolean("lighting.threaded", false);
        chunkSystemSchedulerEnabled = config.getBoolean("parallel.chunk-system-scheduler.enabled", false);
        chunkSystemSchedulerWorkers = Math.max(1, config.getInt("parallel.chunk-system-scheduler.workers", 1));
        chunkSystemSchedulerLockRadius = Math.max(0, Math.min(2, config.getInt("parallel.chunk-system-scheduler.lock-radius", 2)));
        chunkSystemSchedulerSplitStages = config.getBoolean("parallel.chunk-system-scheduler.split-stages", false);
        chunkSystemSchedulerDepGating = config.getBoolean("parallel.chunk-system-scheduler.dep-gating", false);
        chunkSystemEnabled = config.getBoolean("parallel.chunk-system-enabled", false);
        chunkSystemFailFastGuards = config.getBoolean("parallel.chunk-system-fail-fast-guards", true);
        chunkAsyncIoEnabled = config.getBoolean("parallel.chunk-async-io-enabled", true);
        chunkDeserializeThreads = Math.max(1, config.getInt("parallel.chunk-deserialize-threads", 1));
        worldgenRandomCheck = config.getString("parallel.worldgen-random-check", "warn");
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

    /** Persist learned routes to independent JSON file (replaces old YAML append logic). */
    public static void persistLearnedRoutes() {
        try {
            io.izzel.arclight.common.optimization.general.servercore.ownership.LearnedRoutePersistence.saveOnShutdown();
        } catch (Exception e) {
            LOGGER.error("persist-learned-routes failed", e);
        }
    }

    /** 解析 [[x,z],[x,z],...] 为 int[][]；空/非法返回空数组。 */
    private static int[][] parseNestedPairs(String value) {
        String text = value.strip();
        List<int[]> result = new ArrayList<>();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return new int[0][];
        }
        text = text.substring(1, text.length() - 1);
        int idx = 0;
        while (idx < text.length()) {
            int open = text.indexOf('[', idx);
            if (open < 0) {
                break;
            }
            int close = text.indexOf(']', open);
            if (close < 0) {
                break;
            }
            String pair = text.substring(open + 1, close).trim();
            String[] parts = pair.split(",");
            if (parts.length == 2) {
                try {
                    result.add(new int[]{Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim())});
                } catch (NumberFormatException ignored) {
                    // 忽略非法项
                }
            }
            idx = close + 1;
        }
        return result.toArray(new int[0][]);
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

                # 玩家方向区块预取（默认关）：按移动方向对视距外 1..depth 投临时 ticket（到期自动回收）
                chunk-prefetch:
                  enabled: false
                  depth: 6                # 视距外预取深度（格）
                  interval-ticks: 5       # 每玩家预取重算间隔
                  timeout-ticks: 200      # ticket 存活 tick（到期自动回收）

                # 生物间碰撞（默认关）：关闭后生物不阻挡、可重叠穿行；玩家碰撞保留
                mob-collision:
                  enabled: false
                  players-affected: false   # true=玩家也参与关闭（全量，谨慎）

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
                  chunk-demand-min-drain-ms: 2         # 主线程排空最低保证窗口：低 TPS 超预算时仍至少排空该时长（防死亡螺旋；0=关闭）
                  chunk-demand-player-priority: false # chunk 需求玩家距离优先级：按提交时距最近玩家分 4 桶优先消费（默认关）
                  chunk-demand-starve-ticks: 600      # 低优先级桶队头超龄即优先消费（饿死兜底，仅 priority 开启时生效）
                  chunk-system-scheduler:            # 区块系统调度器 M1：FlowSched 移植驱动原版生成 future 链（启动期生效，改值需重启）
                    enabled: false                   # 总开关（关=原版 worldgen 邮箱 FIFO，逐位一致）
                    workers: 1                       # worker 线程数（M1 固定 1 保串行语义；状态机重写后放开）
                    split-stages: false              # 两阶段锁域拆分：features 前只锁中心块，FEATURES 层前换 5×5 锁（默认关）
                  chunk-system-enabled: false        # 区块系统状态机 M2.1：单区块×单状态细粒度任务图（优先于上方 M1 开关；启动期生效）
                  chunk-system-fail-fast-guards: true # M2.2 主线程边界 fail-fast 守卫（仅 chunk-system-enabled 下生效）
                  chunk-async-io-enabled: true       # M2.2 IO 反序列化移出主线程（调度器最低优先级档，仅 chunk-system-enabled 下生效）
                  worldgen-random-check: warn        # M2.2 World.random 跨线程检测：warn/throw/off（仅 chunk-system-enabled 下生效）
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
                  crossref-snapshot-cache: false      # 影子快照单条缓存（证伪实验，默认关；依赖 crossref-value-snapshot: true）
                  belt-passenger-defer: false      # worker 实体撞传送带时 passenger 注册延迟到主线程（配合 route-on-read=false）
                  main-thread-entity-force: []     # 强制主线程 tick 的类名/前缀
                  main-thread-entity-allow: []     # 强制不路由的类名/前缀（危险调试用）
                  persist-learned-routes: false    # 停机时把学到的路由写回配置（仅实体类，最多 200 条）
                  block-tick-main-thread-when-serialized: true # 串行回退路径的方块 tick 在 worker 上时延迟到主线程 POST（默认开）
                  journal-max-per-region: 4096     # 跨区写 journal 每区域队列上限（最旧丢弃）
                  journal-lww-dedup: true          # 同 pos 未应用条目 LWW 合并（重试风暴主防线，默认开）
                  journal-max-per-tick: 512        # 每维度每 tick 提交上限（超出丢弃计 budgetDropped，0=不限）
                  journal-read-back: false         # read-your-writes overlay（预留接口，默认关）
                  determinism-mode: false           # 确定性模式：跨区 journal 按区域序在调度线程统一应用（默认关）
                  be-parallel-allow: []            # BE 三档：允许 region worker tick 的类型（registry key 或前缀*）
                  be-main-thread-force: ["create:track", "lootr:lootr_chest"] # BE 三档：强制主线程类型（尖峰/跨区依赖）
                  create-track-lazy-spread: false   # Create 长轨道假轨光栅化分摊（默认关）
                  create-track-lazy-chunk-blocks: 64 # 分摊时单连接每 tick 最多栅格块数
                  villager-poi-path-budget: 0       # 村民主线程 POI/单目标寻路预算（0=关闭）
                  barrier-soft-degrade: false       # B组:主线程已落后时 barrier 时间切片 join（迟到 region 本轮跳剩余工作,存活实体下 tick 补；默认关,遥测对比后逐个开）
                  barrier-target-ms: 50             # B组:整 tick 目标预算 ms；elapsed>它才激活软降级（0=永不）
                  main-wasted-ms-telemetry: false   # 量化 barrier 等待 vs join 后主线程工作（players/localTicks）的重叠上界（纯遥测）
                  barrier-timeout-action: degrade    # 硬超时行为 crash|degrade（degrade=转主线程串行+自动恢复）
                  process-queue-wake: true           # processQueue 按需唤醒（marshal 请求 tick 间及时服务）
                  on-fault-fallback-vanilla: false    # 连续 3 次 barrier 硬超时后退原版串行（重启恢复；默认关）
                  chunk-env-parallel: false           # 区块环境 tick(随机/流体)并行：主线程 tickChunks 扇出到独立子任务池（默认关）
                  chunk-env-threads: 0                # 子任务池大小（0=auto=CPU）
                  chunk-env-lock: true                # 3×3 区块锁：互斥相邻 chunk 并发写（默认开）
                  portal-async: false                  # 异步传送门：worker 上目标区块未 FULL 时提交异步加载并延后一 tick（默认关）
                  entity-batch-parallel: false          # 实体批并行：region worker 实体阶段扇出到独立子任务池（默认关）
                  entity-batch-threads: 0               # 批子任务池大小（0=auto=max(2, CPU-region_count)）
                  entity-batch-allow: []                # 批并行白名单（显式放行 modded 类，registry key 或前缀*）
                  entity-batch-deny: []                 # 批并行黑名单（强制排除，优先级高于白名单）
                  barrier-timeout-recover-ticks: 6000 # degraded 自动恢复并行所需的连续正常 tick

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
