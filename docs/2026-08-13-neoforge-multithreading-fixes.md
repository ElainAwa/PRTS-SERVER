# NeoForge 1.21.1 多线程引擎审查修复记录

> **文档性质**：AI 创作（由 WorkBuddy 根据 2026-08-12/13 对 PRTS 服务端 NeoForge 1.21.1 多线程实现的专项审查与修复生成）。
> **审查基线**：分支 `1.21.1-Multithreading`，commit `8c82100`（chore: quiet async-pathfinding hot-path logs）。
> **修复范围**：`arclight-common` 共享运行时（并行 tick / 异步寻路 / 区块需求 / 异步 IO / 网络与本地输入安全）。
> **验证**：`:arclight-common:compileJava` + `:bootstrap:neoforgeJar` 均 BUILD SUCCESSFUL；真实 NeoForge 启动冒烟、三维度同坐标 chunk 压测、扩缩容+寻路集成压测、NBT 超限故障注入全部 PASS。

---

## 一、P0 修复（跨维度状态损坏 / 竞态根因）

### 1. 区块需求跨维度串队
- **文件**：`optimization/general/servercore/ChunkDemandQueue.java`、`.../dimension_parallel/ServerChunkCacheMixin_DimParallel.java`、`.../GenerationChunkHolderMixin_CompleteNotify.java`
- **问题**：全局 `PENDING/SEEN/WAITERS` 以裸 `ChunkPos.asLong` 为键，不同维度同坐标串队、错误完成 Future；容量检查非原子；等待者超时后泄漏。
- **修复**：键升级为 `DemandKey(ServerLevel, chunkPos)`；drain（`poll(level)`/`afterDrain(level)`）只消费当前维度；容量 CAS 原子化；waiter 超时/中断清理；完成通知仅限 `ChunkStatus.FULL + LevelChunk`。
- **溯源**：PRTS 自研统一异步区块需求调度（v03 统一异步调度）。

### 2. 区域计划 tick 维度串投
- **文件**：`optimization/general/servercore/RegionTickManager.java`、`.../region_parallel/LevelTicksMixin_RegionBlockTick.java`
- **问题**：全局 `IN_REGION_TICK` 被当作线程归属判断，Overworld 区域并行阶段会把 Nether/End 的 scheduled tick 拦截并错投。
- **修复**：`ThreadLocal<RegionContext(regionId, level)>` 真实 worker 归属；延迟队列携带 owner `LevelTicks` 按原维度回投；拦截条件改为 `isRegionWorker()`。
- **溯源**：PRTS 自研区域并行引擎。

### 3. 异步寻路提交与扩缩容竞态
- **文件**：`optimization/general/servercore/AsyncPathfindingManager.java`、`.../async_pathfinding/PathFinderMixin_Async.java`
- **问题**：先 `submit` 后 `markAsyncPending()`，worker 可能读 false 后丢弃任务导致导航永久 pending；`reconfigureRegions` 直接覆盖旧结果桶丢 in-flight 结果。
- **修复**：先 `reservePending()` + `markAsyncPending()` 再投递，异常回滚；扩缩容持锁迁移旧桶结果（`i % n`）或回落主队列。
- **溯源**：PRTS 自研异步寻路（参考 ServerCore pathfinding 优化思路，实现为独立 manager）。

### 4. ChunkMap 实体索引并发写崩溃（压测发现）
- **文件**：`.../region_parallel/ChunkMapMixin_EntityIndexLock.java`（新增）
- **问题**：region worker 上实体死亡掉落 → `addFreshEntity` → `ChunkMap.addEntity` → fastutil `entityMap.put` 并发 rehash，`ArrayIndexOutOfBoundsException`（真实压测崩溃复现）。
- **修复**：`@Redirect` 包 `EntityLockManager.INDEX_LOCK.writeLock()`，与 `EntityLookup/EntitySectionStorage/EntityTickList` 既有锁方案一致。
- **溯源**：PRTS 自研区域并行引擎的实体索引治理（复用 `EntityLockManager`）。

---

## 二、P1 修复（并发容器 / barrier 健壮性 / 线程归属）

- **WeakHashMap 并发**（`RegionTickManager`）：`MAIN_THREAD_BLOCK_ENTITIES` 改 `Collections.synchronizedMap` + 显式 `synchronized`（`computeIfAbsent` 是 default 方法，包装 Map 不会自动同步）。
- **dragonParts 并发**（`EntityCallbacksMixin_DimParallel`）：fastutil `Int2ObjectMap` put/remove 统一 `synchronized(map 实例)`。
- **Barrier 标志 finally 复位**（`DimensionTickManager.parallelTick` / `RegionTickManager.runWorkers`）：超时/中断/拒绝不再使 `IN_DIMENSION_TICK`/`IN_REGION_TICK` 卡死；`POOL.execute` 拒绝时 `latch.countDown()` 防悬挂。
- **异步 IO 有界化**（`RegionFileStorageMixin_AsyncIO`）：`POOL` volatile + 双检锁安全发布；有界队列 256 + `CallerRunsPolicy`。
- **配置校验**（`PRTSFeaturesConfig`）：`chunk-demand-per-tick < 1` 回退默认 50。
- **线程归属判断全面迁移**：全局标志 → 真实线程（`isDimensionTickThread()` 线程名 / `isRegionWorker()` ThreadLocal），涉及 `SimplePluginManagerMixin`、`EntityMixin_DimTransfer`、`ChunkMapMixin_WorkerGenerationGuard`、`ServerChunkCacheMixin_DimParallel`、`ServerChunkCacheMixin_ChunkDemandDrain`、`PersistentEntitySectionManagerMixin`、`LevelChunkMixin_RegionCrossWrite`、`LevelMixin_RegionCrossWrite`、`ItemEntityMixin_RegionMerge`。`ServerWatchdogMixin_BarrierAware` 保留阶段活跃语义。

---

## 三、P2 修复（安全加固 / 性能）

- **玩家 NBT 有界解析**（`PlayerDataStorageMixin`）：`NbtAccounter.unlimitedHeap()` → `create(256MB)` + 压缩文件 32MB 预检（两处加载路径）。溯源：Arclight/CraftBukkit 玩家数据加载路径的 PRTS 加固。
- **握手 hostname 上限**（`ClientIntentionPacketMixin`）：`readUtf(Short.MAX_VALUE)` → `max(原值, 4096)`。溯源：Arclight BungeeCord hostname 兼容的 PRTS 加固。
- **寻路快照零分配**（`PathNavigationRegionMixin`）：逐格 `new BlockPos` → 复用 `MutableBlockPos`。
- **结果积压有界化**（`AsyncPathfindingManager`）：`RESULT_BACKLOG` 超 4096 丢弃并清 pending；`offerResult()` 统一入队 + 扩缩容竞态回落。
- **配置解析防炸弹**（`ServerCoreConfig`）：`SafeConstructor` + `LoaderOptions`（别名 ≤50、码点 ≤1MB）。溯源：移植自 ServerCore by Wesley1808（GPL-3.0），PRTS 加固。
- **热点日志守卫**（`PathFinderMixin_Async`）：DEBUG 日志包 `isDebugEnabled()`。

---

## 四、验证记录

| 验证项 | 结果 |
|---|---|
| `:arclight-common:compileJava` | PASS（仅历史 Mixin remap 警告） |
| `:bootstrap:neoforgeJar`（含 installer.json） | PASS |
| 真实启动冒烟（flat 世界, JDK 21, -Xmx3G） | PASS，`Done (3.100s)`，20 TPS 稳定 |
| 维度并行（overworld/nether/end） | PASS，ticks=601→7801 稳定 |
| 三维度同坐标 chunk（forceload 512,512） | PASS，无串队，region 文件全部生成 |
| 僵尸死亡掉落（ChunkMap.entityMap 并发） | 修复前崩溃复现，修复后 5 分钟无异常 |
| 自动扩缩容（interval=20s 压测配置） | PASS，`4 -> 2 low-load` 真实触发 |
| 优雅关服（stop） | PASS，三维度 Saving chunks |
| NBT 超限注入（33MB 文件 / 300MB 载荷 / 正常数据） | 全 PASS |

**噪音识别（非错误）**：安装器 `Can't Find Class` 批量探测、库下载 `SocketTimeoutException`、AE2LT 模组未装类探测 WARN、新建 flat 世界 `No key layers` datafix 提示。

---

## 五、遗留事项

- 异步寻路统计在 flat 压测环境为 0 次（无寻路目标），需真实服务器观察。
- 尚未做：`arclight-neoforge:test`（原 NO-SOURCE）自动化回归；P2 之后暂无未决代码项。
- 建议在正式测试服运行完整存档周期后再考虑发布。
