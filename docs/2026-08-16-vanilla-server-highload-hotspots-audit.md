# 原版 Minecraft 服务端「烂操作」高负载审计与兼容安全优化方向

> **文档性质**：AI 创作（针对 PRTS 服务端 1.21.1 多线程引擎的再审计，聚焦「原版服务端本身就很烂的高负载计算」）。
> **审查基线**：分支 `1.21.1-Multithreading`，HEAD `539c1260`（fix: isolate block-entity worker tick failures）。
> **审查范围**：`arclight-common` 的 `optimization/general/servercore`（维度并行 / 区域并行 / 异步寻路 / 异步 IO / 统一区块需求）与 `optimization/general`（追踪 / 邻居 / 网络 / 随机 tick / 形状）。
> **文档目的**：只分析、只给方向，**不写代码**。逐一指出原版服务端哪些操作天然低效、当前已优化到哪一步、剩余缺口有哪些、以及**在保证「我们主动兼容第三方模组、而非让模组反过来兼容我们」前提下的优化策略**。
>
> **⚠️ 本文档初稿为「纯理论 vanilla 热点审计」；2026-08-16 重读 `docs/PRTS-multithreading-techdoc (1).html` §1 后补入实测瓶颈（见 §〇·一），并据此重排优先级。核心结论修正：生产服实测第一大瓶颈不是任何 vanilla 热点，而是模组事件洪流 `EventBus.post`。**

---

## 〇、核心思考框架

原版 Minecraft 服务端（`MinecraftServer` + `ServerLevel`）本质是**单线程、全量扫描、无预算、无索引**的换代架构。它不是一个「边角可优化」的软件，而是把「世界模拟」整体压在一个线程上：

1. **单线程换代**：`tickServer → tickChildren → 每个维度 tick`，整个世界串行。
2. **状态无隔离**：实体、方块实体、LevelTicks、光照、POI 全部共享同一份可变世界状态，没有任何线程边界。
3. **每次 tick 全量扫描**：所有实体、所有方块实体、所有加载区块的随机 tick，无惰性、无索引。
4. **无预算的链式反应**：邻居更新、红石 power 重算、光照传播、活塞移动，一个方块变更可以无上限级联。
5. **同步 IO 与同步加载**：区块读写、区块加载、寻路目标跨区块，全部在主线程阻塞。

**第三方模组兼容的核心悖论**：绝大多数 mod 建立在「世界状态单线程、主线程独占」的隐式假设上。任何把负载挪出主线程/并行的优化，都可能与 mod 的隐式假设冲突。因此本项目的铁律是：

> **我们兼容第三方模组，而不是第三方模组兼容我们。** 优化必须能感知 mod、能降级回 vanilla 语义、能按需关闭；遇到没见过的 mod，默认退回 vanilla 行为，而不是让 mod 崩溃。

PRTS 已经沉淀出的三层兼容策略（本文每处缺口都会套用这套策略评估）：

| 层级 | 机制 | 现状 | 例证 |
|---|---|---|---|
| **① 检测** | 检测他人优化器存在则整体让位 | `@LoadIfMod(ModCondition.ABSENT)` | `ClassInstanceMultiMapMixin` 对 Lithium/Canary/Radium 让位 |
| **② 降级** | 检测 mod 破坏并行语义则回退串行 | `serializeBlockTicksForMods()` | 存在 `alternate_current` 时方块 tick 回退单线程 |
| **③ 路由** | 识别 mod 专属主线程/尖峰依赖，定向路由 | `be-main-thread-force` / `main-thread-entity-force` | `create:track` 铁主线程、`minecolonies` 主线程 tick、`ae2lt` SetWorking 节流 |
| **④ 逃逸阀** | 全部优化可按配置关闭 | `prts-features.yml` / `servercore.yml` | `enabled: false` 整体回退 vanilla |
| **⑤ 兜底** | 未见过的新 mod 默认走 vanilla 语义 | `thread-policy: stats/enforce/off` | worker 违规只统计不拦截，生产安全 |

---

## 〇·一、实测瓶颈补正（重读 HTML 技术文档后，最重要的一处修正）

初稿纯按 vanilla 热点推理，把「光照/漏斗/红石」列为头号目标。但 `docs/PRTS-multithreading-techdoc (1).html` §1（生产服 Spark 实测 2026-08-05~06）给出了**决定性证据**：

| 采样 | 负载 | TPS | MSPT | 结论 |
|---|---|---|---|---|
| `UeGinzA5gK` | 9 人 | 20.00 | 中位 33–39ms | 中等负载 |
| `profile-22.53.40` | 13 人 | 16.6/17.2/17.3 | 中位 52ms，p95 71ms，**max ~1900ms** | 掉帧实锤 |
| `BGDV85kqm2` | 12 人 | 17.1/14.2/15.0 | 中位 52–59ms，p95 105ms，**max 751ms** | 最卡：TPS 掉到 14 |

**根因链（bottleneck 归因）：**

| 热点 | inclusive 占比 | 性质 |
|---|---|---|
| `EventBus.post` 子树 | **57% → 75.5%** | **模组事件洪流**：破方块类 mod 触发 Forge 事件，多态派发 + `HashMap` 查找 + **锁竞争**被放大 |
| `AdvQuarryEntity.breakBlocks`（QuarryPlus） | 31.6% → 16.8% | **整列破块每块发 `BlockEvent`** |
| `DestructionTaskState.copyTags`（RTSbuilding） | ~7.2% | 破坏任务在事件总线做 **NBT 拷贝** |
| `ServerChunkCache.tickChunks` | 16.3% | **结构性基线**（sim=10 × 2.2 万区块），非 mod 造成 |
| `native` | 18.77% | 纯下游症状（派发+锁+写盘），源头减量后回落 |
| **PRTS 核心引擎税** | **仅 2.56%** | 已证伪「PRTS 核心卡顿」嫌疑 |

**这彻底改变了优先级框架：**

1. **「最卡顿的是世界计算」已在此被证伪。** 网络与异步 IO 早已异步化；PRTS 把实体/维度/寻路搬上 worker 后，主线程剩下的真实大头是**模组事件派发**——不是 vanilla 的热点，而是「vanilla 提供、mod 疯狂触发」的 `getBlock`/`setBlock`/`BlockEvent` 事件链。
2. **我初稿的 vanilla 热点（光照/漏斗/红石）依然真实存在且长期有效**，但它们是「结构性基线」，不是当前生产服的边际瓶颈。真正的**边际收益**在「给模组事件洪流降本」。

因此，更新的优先级把**「削减模组事件链成本」**提到与 vanilla 热点并列的第一梯队（见 §三、§五）。这与 HTML 技术文档 §14.3「未来演进」里 `max-chained-neighbor-updates=1000000 → 16/≤1000`、开 `reliable-chunk-save` 的「生产减压」诉求完全一致。

> **兼容红线提醒（关键）**：`BlockEvent` 是 mod 赖以工作的公开 API。**绝不能吞掉或延迟 `BlockEvent`**——那会破坏所有监听破块/放块的 mod（QuarryPlus、RTSbuilding、各种自动化）。优化只能做**「事件的内部成本」**（派发查找、锁、NBT 拷贝、重复的 `getBlockState`），不能做**「事件的数量/时机」**。换句话说：事件必须照发，但发得快。

---

## 一、系统性问题（架构层面，已优化 vs 剩余）

| # | 原版烂点 | 当前状态 | 实现位置 |
|---|---|---|---|
| 1 | 维度串行 tick | ✅ 已优化：维度并行 | `DimensionTickManager`（无玩家维度上 worker，有玩家维度留主线程） |
| 2 | 实体 tick 串行 | ✅ 已优化：区域并行 | `RegionTickManager.dispatchAndTick`（按区块列分区） |
| 3 | 方块实体 tick 串行 | ◐ 已部分优化 | `RegionTickManager.runBlockEntityTickPhase`（默认关，三档调度） |
| 4 | 计划方块 tick / 红石 | ◐ 已部分优化 | 区域方块 tick 并行 + 邻居更新熔断 |
| 5 | 随机方块 tick | ✅ 已优化 | `ticking/random/*`（雷击/结冰用廉价计数器，去重复流体 tick） |
| 6 | 寻路 | ✅ 已优化 | `AsyncPathfindingManager` + `PathFinderMixin_Async` + 零分配快照 |
| 7 | 实体追踪/网络发包 | ◐ 已部分优化 | `entitytracking/*`、`trackingrange/*`、`nearbyplayers/*` |
| 8 | 区块 IO | ✅ 已优化 | `RegionFileStorageMixin_AsyncIO`（异步有界线程池） |
| 9 | 区块加载/需求 | ✅ 已优化 | `ChunkDemandQueue` 统一异步需求调度 |
| 10 | 自然生成 | ✅ 已优化 | `mob_spawning/*`、`biome_lookups/*`、`tickets/*` |
| 11 | 邻居更新风暴 | ✅ 已优化 | `LevelMixin_NeighborUpdateCircuitBreaker` + `PRTSFeaturesConfig` |
| 12 | 物品/经验球合并 | ✅ 已优化 | `features/merging/*` |
| 13 | 村民/动物繁殖失控 | ✅ 已优化 | `breeding_cap/*`、`VillagerMixin_Lobotomize` |
| 14 | 自动存档 | ✅ 已优化 | `MinecraftServerMixin_Autosave` |
| 15 | **光照引擎** | ◐ 已部分优化 | `lightengine/*`（预算化 + 遥测，`79406ea4`；无传播算法重写） |
| 16 | **实体 AABB 空间查询** | ❌ **未优化（大缺口）** | 无 `getEntities(AABB)` 空间索引 |
| 17 | **活塞批量移动** | ◐ 仅激活范围修复 | 无批量移动优化 |
| 18 | **液体流动** | ◐ 仅随机 tick | 无流动批量优化 |
| 19 | **区块保存全量 NBT** | ◐ 仅 WAL + 异步 IO | 无序列化减负 |
| 20 | **红石 dust power 重算** | ◐ 仅熔断 | 无重算风暴根治 |

> 结论：**架构层面已经把「主循环换代」这条最粗的腿（维度/实体/方块实体/寻路/IO）基本搬空**；**光照引擎**也已于 `79406ea4` 落地「预算化 + 遥测」（传播成本仍高于理论最优，但已从「完全未动」变为「有预算、可观测」，见 §3.5）。剩余的大头集中在**各类「链式反应」**（红石 power、邻居更新、活塞、液体）——它们无法靠并行搬走，只能靠「削减无谓重算 + 预算化」根治。

---

## 二、逐热点审计（按 `ServerLevel.tick` 主循环顺序）

### 阶段 1：玩家与实体 tick

#### 1.1 实体 tick 串行（原版最大热点）
- **原版机理**：`ServerLevel.tick → entityTickList.forEach` 逐个调 `tickNonPassenger`，含碰撞、AI、移动、状态更新。原版在**一个线程**上把所有实体的死亡/拾取/推挤/交互串完。
- **已优化**：
  - 维度并行里，无玩家维度上 worker（`DimensionTickManager`）。
  - 维度内，overworld 按区块列分区（`RegionLevel.STRIPE_WIDTH`），`dispatchAndTick` 把玩家/物品/经验球留在主线程，其余实体按归属分区上 `PRTS-RegionTick-*` worker。
  - 装置/殖民地等依赖主线程 `getBlockEntity` 的实体，由 `needsMainThreadTick` 三档判定（force 列表 → allow 列表 → 手工前缀种子 → 违规学习台账 `ClassAffinityLedger`）路由回主线程。
- **剩余缺口**：实体 tick 的**碰撞子阶段**仍是最贵的（见 1.3）。

#### 1.2 实体追踪与网络发包
- **原版机理**：`ChunkMap` 对每个被追踪实体，每 tick 向所有在追踪范围内的玩家发送位置/旋转/装备/状态 delta（`TrackedEntity` `sendDirtyEntityData`）。玩家越多，每实体发包数越多，O(玩家×实体)。
- **已优化**：`entitytracking/*`（`AreaMap` 空间索引替代全量扫描追踪范围内的玩家）、`trackingrange/*`（可配置追踪距离）、`nearbyplayers/*`（`NearbyPlayerIndex` 空间索引算「附近玩家」）、`network/*`（连接/发包优化）。
- **剩余缺口**：实体**可见性 diff 已做，但「脏数据」的合并与发包频率仍由原版语义决定**。可考虑对高频小实体（物品、经验球）降频发包，但需非常克制——mod 可能依赖精确的显示状态（如隐蔽机械、展示实体）。

#### 1.3 实体碰撞检测与 `getEntities(AABB)` 空间查询 ⭐缺口
- **原版机理**：`Entity.getBoundingBox` 与 `Entity.getCollisions`；`Level.getEntities(Entity, AABB)` 通过 `EntitySectionStorage` 把 AABB 落进 `EntitySection` 后**线性遍历该 section 的所有实体做盒-盒相交**。原版对「某区域有哪些实体」没有真正的空间索引，section 内是裸 `List`。
- **为什么烂**：高密度场景（刷怪塔、养鸡场、掉落物山、末影龙战）下，一次 `getEntities(AABB)` 就是 O(该 section 实体数) 的线性扫描，且**每次 tick 被反复调用**（AI 探测、碰撞、玩家交互、`item.merge` 等）。
- **已优化**：仅 `RegionTickManager` 施加了 section 级锁（`EntitySectionMixin_RegionLock`）保证并行下不崩，**没有减少扫描本身**。
- **兼容安全优化方向**：
  - **给 section 内实体加懒散空间/类型索引**（如按 `MobCategory` 或按小网格分桶），把「扫描全 section」改为「只扫命中网格」。**关键兼容点**：必须保留 `getEntities` 的**返回语义**（含顺序、去重、是否含自身），且对 mod 自定义实体类型**不区分**（索引只按原版已有属性分桶，不按类名特判）。检测到 Lithium/Canary 等已做此优化的 mod 时整体让位（`@LoadIfMod ABSENT`）。
  - **风险**：返回顺序若被 mod 依赖（如拾取顺序、AI 选目标顺序），分桶会改变结果。建议**只对「无玩家交互的纯空间查询」加速**，玩家交互路径保留原版线性扫描。

#### 1.4 实体类型查找 `getEntities(Class)`
- **原版机理**：`ClassInstanceMultiMap` 按类维护 `byClass` 映射。原版给每个 `EntitySection` 建一张 `ClassInstanceMultiMap`，`getEntitiesOfClass` 走 `byClass.get(clazz)`。
- **已优化**：`ClassInstanceMultiMapMixin` 惰性化 byClass 映射（大多数 section 只有少数类型的实体，原版预建全类索引浪费）。**且对 Lithium/Canary/Radium/Recruits 让位**——这是「检测他人优化器则让位」的典范。
- **无剩余缺口**。

#### 1.5 寻路（PathFinder）
- **原版机理**：A*，`PathFinder` 每步为每个节点 `new Node`、维护 `HashSet` open/closed、`BinaryHeapPriorityQueue`。每个寻路中的 mob 每次导航步都重建。原版在主线程同步算，跨区块还会同步加载区块。
- **已优化**：
  - `AsyncPathfindingManager`：寻路提交到 worker，结果回投所在线程（`PathFinderMixin_Async`），主线程只 drain 结果。
  - `PathNavigationRegionMixin`：寻路快照零分配（复用 `MutableBlockPos`）。
  - `PathNavigationRegionMixin_RegionSafe` / `GroundPathNavigationMixin_RegionSafe`：区域并行下寻路安全。
  - `ImmutableBlockView` / `ImmutablePathNavigationRegion`（`c9b4b6fc` 提取复用不可变方块视图）：worker 上寻路读世界用不可变快照，**不碰主线程可变状态**。
- **剩余缺口**：异步寻路**结果积压**（`RESULT_BACKLOG > 4096` 丢弃）在高峰会丢失导航请求；`AsyncPathfindingManager` 文档自述「flat 压测环境寻路统计为 0，需真实服务器观察」。这些是**工程收敛**而非新方向。

#### 1.6 附近的玩家查找（getPlayers in range）
- **已优化**：`nearbyplayers/*` 空间索引。无剩余缺口。

### 阶段 2：方块与方块实体

#### 2.1 方块实体 tick（漏斗 / 熔炉 / 输送带）
- **原版机理**：`ServerLevel.tickBlockEntities → TickingBlockEntity 列表逐个 tick`。**漏斗是全服最贵方块实体之一**：每 tick 做两次（顶部/底部各一次）传输检查，每次检查遍历上方/下方容器。熔炉、自动输出同理。原版对「无物品可动的漏斗」毫无减负。
- **已优化**：
  - `RegionTickManager.runBlockEntityTickPhase`：BE tick 参与区域并行（**默认关**——BE 间交互复杂，并行有竞态风险）。
  - `BlockEntityTickStats` + 三档调度（`be-parallel-allow` / `be-main-thread-force` / 台账自动降级）——`539c1260`「isolate block-entity worker tick failures」把任何 BE 并行异常隔离为「该类型本会话降级回主线程」，**不让单个 mod 的 BE 竞态升级成服务端崩溃**。
  - `TrackBlockEntityMixin_LazySpread`（Create 假轨）、`TeslaCoilBlockEntityMixin_SetWorkingThrottle`（ae2lt 节流）。
- **剩余缺口（值得做）**：**漏斗空转检测**。原版漏斗只要「上方有方块/下方有容器」就每 tick 探测，即使 128 天没动过。可对「上一 tick 无任何传输成功」的漏斗降低检查频率（如每 4/8 tick 一次），这是 Mob 农场类服务端最典型的痛点。**兼容点**：只对「无物品可动的漏斗」降频，一旦有物品进入立即恢复全频；且必须可配置关闭（有 mod 依赖漏斗精确 tick 时序，如物流 mod、自动化 mod）。

#### 2.2 计划方块 tick / 红石（LevelTicks）
- **原版机理**：`LevelTicks.run` 用按 tick 频率分桶的链表跑计划 tick；`runBlockTick` 做 `getBlockState` 并回调 `block.tick`。红石线（`RedStoneWireBlock`）每次更新重新计算周围 power，且**每次 power 变化再触发邻居更新**，形成 O(n²) 级联。
- **已优化**：
  - 区域并行把计划方块 tick 分区（`LevelTicksMixin_RegionBlockTick`），跨区写走 journal。
  - `LevelMixin_NeighborUpdateCircuitBreaker`：邻居更新风暴熔断（`max-per-tick: 200000`），防看门狗强杀。
  - `serializeBlockTicksForMods()`：检测 `alternate_current`（红石网络重算 mod）则**整体回退串行**——因为其连线图非线程安全，并行会撕裂网络节点引用。这是「检测 mod 破坏语义则降级」的又一典范。
- **剩余缺口（根治方向）**：熔断只「止血」不「治本」。真正的优化是**红石 dust 的 power 重算风暴**：当收到邻居更新时，dust 已缓存自身 power，若传入信号未变，原版仍会重算一次并可能再广播。可做「power 缓存 + 幂等短路」（传入 power 与缓存一致则跳过重算与广播）。**兼容点**：红石是 mod 最敏感的机制之一（`alternate_current` 已全军押注），任何改动必须：默认关、`serializeBlockTicksForMods` 检测到红石 mod 则整体让位、且只缓存「纯 sync 的 dust power」不碰其他组件。

#### 2.3 邻居更新链式反应
- **原版机理**：`Level.setBlock → updateNeighboursAt → for each of 6 邻位 neighborChanged → 可能再 setBlock → 再 updateNeighbours`。一个方块变更可无上限级联。
- **已优化**：熔断（`neighbor-update-breaker`）；`LevelMixin_RegionNeighborShapeLock` / `ServerLevelMixin_RegionNeighborLock`（并行下锁定邻居访问）。
- **剩余缺口**：熔断的**阈值语义**。当前是「每 tick 超过 max 次就熔断」，但一次合法的大型活塞/红石工程可能在单 tick 合法触发大量邻居更新。更精细的预算化（按来源方块/按面积分摊）可减少误熔断，但复杂度高、收益边际。**建议保持现状，不引入新风险**。

#### 2.4 随机方块 tick（tickChunk）
- **原版机理**：`ServerLevel.tickChunk` 对每个加载区块，按 `randomTickSpeed`（默认 3）对区块内随机方块做 `randomTick`；另含雷击/结冰/积雪判定（每区块每 tick 多次 `nextInt`），以及**与后续流体随机 tick 重复**的液体判定。
- **已优化**：`ticking/random/*`（`ServerLevelMixin_Random`、`LevelChunkMixin_Random`、`ServerChunkCacheMixin_Random`）：雷击用每区块自持倒计时（`arclight$lightningTick`），结冰/积雪用单次随机 + 逐区块自增，且跳过已在 `tickChunk` 里处理过的重复流体随机 tick。`LiquidBlockMixin_Random` 一并处理。
- **无剩余缺口**（这是原版「烂」得最干净、也最标准的一处，已彻底解决）。

### 阶段 3：区块管理

#### 3.1 自然生成（NaturalSpawner）
- **已优化**：`mob_spawning/*`（mobcap 强制、类别间隔、额外容量）、`biome_lookups/*`（缓存噪声 biome）、`tickets/*`（生成时不加额外 ticket）、`MobCategoryMixin`。**无剩余主要缺口**。

#### 3.2 区块加载 / 需求（chunk demand）
- **已优化**：`ChunkDemandQueue` 统一异步需求调度（`DemandKey(ServerLevel, chunkPos)` 跨维度隔离、容量 CAS、waiter 超时清理）、`ServerChunkCacheMixin_ChunkDemandDrain` 主线程每 tick 预算 50、`sync_loads/*`（bee/寻路/地图/结构检查只在已加载区块时同步）。**无剩余缺口**。

#### 3.3 区块生成
- **已优化**：`ChunkMapMixin_GenerationBudget` / `ChunkMapMixin_WorkerGenerationGuard`（worker 上生成防护）、`generation-tasks-per-tick: 50` 削峰、`chunkgen-inflight-limit: 128` 滚动窗口。**无剩余缺口**。

#### 3.4 区块 IO 与保存
- **已优化**：`RegionFileStorageMixin_AsyncIO`（异步有界线程池 + CallerRunsPolicy）、`reliable-chunk-save`（WAL 预写日志）。
- **剩余缺口（值得做）**：**ChunkSerializer 全量 NBT 序列化**。原版保存一个区块时，`ChunkSerializer.write` 把区块内**所有方块状态、方块实体、计划 tick、实体、光照**整体序列化，即使只有 1 个方块变过。存盘 tick (`autosave-interval`) 时全量重写，是 IO 尖峰主因。可做「**增量序列化**」：只重写脏（dirty）区块的差异部分，或对未变区块跳过写盘。**兼容点**：增量序列化必须与 mod 的 `BlockEntity.saveAdditional` / `Entity.saveAdditional` 完全一致（mod 可能依赖全量保存的幂等性），且**保存的完整性语义不能变**（崩溃恢复仍需能还原）。建议先做「未变区块跳过写盘」这种最保守的惰性，不做差异合并。

#### 3.5 光照引擎（LightEngine）⭐最大单点缺口 → 已部分优化（预算化 + 遥测，`79406ea4`）
- **原版机理**：`LightEngine` / `ServerLightEngine` 在主线程处理方块变更引起的光照更新。`LevelLightEngine.checkBlock` 在有方块变更时，沿六个方向做**光照传播**（BFS/DFS 遍历相邻区块的 light 队列），天空光照在区块加载/生成时也要传播。光照是原版**除实体 tick 外最贵的主线程子阶段**，且极其模块化、难以并行。
- **为什么是最大缺口**：任何 mod 的方块放置/破坏/生长都会触发 `checkBlock`。高密度机械（Create 传动、AE2 网络、红石工程）每秒成百上千次 `setBlock`，每次都是一次光照传播。**现状（`79406ea4` 后）**：已实现「每 tick 传播预算 + 遥测」（`lightengine/*`），但未重写传播算法本身——预算只限制传播速率，单次 BFS 的单位成本依旧。
- **兼容安全优化方向**（按保守度排序）：
  1. **最保守（推荐先做）**：**光照更新预算化/卸载低优先级**。在 `LightEngine` 的传播队列上施加每 tick 预算，light queue 拥挤时下一 tick 继续，避免单 tick 光照风暴卡死主线程。**语义完全不变**（最终光照一致，只是延迟），mod 无感知。这是所有光照方案里唯一「零破坏」的。**✅ 已实现（`79406ea4`）**：`LightEngineMixin_LightBudget` 在 `propagateIncreases`/`propagateDecreases` 的排空循环上施加每 tick 预算（`LightBudget` 状态机），超出部分顺延下一 tick；`prts-features.yml` 新增 `lighting:` 段（`budget-enabled` / `budget-per-tick` 默认 100000 保守 / `telemetry-enabled`），配 `[light-engine]` 遥测日志（`LightEngineStats`）按实测调阈值；对 C2ME/Lithium/Canary/Radium 让位（`@LoadIfMod ABSENT`）。
  2. **次保守**：跳过「不影响光照」的 `setBlock` 的光照重算。检测 `newState.getLightEmission == oldState.getLightEmission && getLightBlock 两侧均为 15`（不透光且不发光）时跳过 `checkBlock`。原版对「不透明方块互相替换」仍走光照，实际是浪费。**兼容点**：`getLightBlock` / `getLightEmission` 是 mod 可覆写方法，必须每次调用（不能缓存 state 的返回值，因为 mod 可能动态改变），只做一个「两侧都 15 且不发光 → 跳过」的短路，安全。

     > **⚠️ 2026-08-16 更正（本项目已实测确认）**：方案2 在 1.20+ 上**几乎冗余**，本 fork **不实现**。vanilla 的方块变更光照门 `LightEngine.hasDifferentLightProperties(level, pos, old, new)`（在 `LevelChunk.setBlockState` 调用，line 277）体为 `old.getLightBlock != new.getLightBlock || old.getLightEmission != new.getLightEmission || old.useShapeForLightOcclusion() || new.useShapeForLightOcclusion()`。对「两侧 `getLightBlock==15` 且 `getLightEmission` 相等」的替换，前两项均为 false；而 `useShapeForLightOcclusion` 对完全不透明（lightBlock==15）的方块恒为 false（它是为非满立方体/玻璃类方块而设），故第三项亦 false → 该方法已返回 `false` → `checkBlock` 已被跳过。结论：**12.0+ 原版已内置该短路**，再实现只是重复读同样的（已缓存）字段，几乎零收益。若真要实现，必须带 `useShapeForLightOcclusion` 守卫（否则对 mod 中「lightBlock==15 但 shape 依赖」的方块替换会错误跳过，丢光照）；而加了守卫后与 vanilla 现有逻辑完全等价。故方案2 不再实现，仅保留方案1（预算化）+ 遥测。
  3. **激进（暂不建议）**：光照传播异步化 / 并行化（C2ME 路线）。需要把 `LevelLightEngine` 的整个队列语义搬到 worker，且与区域并行、维度并行的锁模型叠加，复杂度极高、mod 兼容面大（任何读 `getBrightness` / `getRawBrightness` 的 mod 都会受影响）。**建议明确不做**，除非出现真实的实测需求。

#### 3.6 活塞批量移动 ⭐缺口
- **原版机理**：`PistonBaseBlock` 移动时，算 `MovedBlock` 列表、`Level.setBlock` 逐个移动方块、再 `moveCollidedEntities` 推实体。大量活塞（活塞电梯、活塞门、刷石机）同时动作时，`setBlock` + 邻居更新 + 光照 + 实体推挤全量级联。
- **已优化**：仅 `PistonMovingBlockEntityMixin_ActivationRange`（让移动中的活塞方块实体在激活范围外不 tick，属激活范围修复，非活塞本体优化）。
- **剩余缺口**：**批量移动的原子化**。原版逐方块 `setBlock`，中间态会触发不必要的邻居更新/光照。可做「一次移动提交一批 `setBlock`，批内抑制中间的邻居更新与光照，批末统一结算」。**这是高风险改动**：活塞是 mod 机械（Create、红石工程）最核心依赖，批内抑制中间态会改变 mod 可观察到的分步行为。**兼容点**：必须默认关、可配置，且只对「纯原版活塞属性」生效——检测到 Create/任何活塞增强 mod 则让位。即便如此破坏面仍大。**建议：列为研究项，不急着做**，除非实测活塞是瓶颈。

### 阶段 4：跨维度与主循环

#### 4.1 维度串行
- **已优化**：`DimensionTickManager.parallelTick`（无玩家维度上 worker，per-tick barrier）。`6ea185b4`「stagger MineColonies citizen work AI phase」在维度内错峰。**无剩余缺口**。

#### 4.2 自动存档
- **已优化**：`MinecraftServerMixin_Autosave`（可配间隔）。**无剩余缺口**。

#### 4.3 看门狗
- **已优化**：`ServerWatchdogMixin_BarrierAware`（并行 barrier 期间不误杀主线程）、`PRTSFeaturesConfig.watchdogEnabled`。**无剩余缺口**。

---

## 三、重点缺口汇总（按「原版烂 + 实测瓶颈 + 收益 / 兼容风险」排序）

> **排序说明**：初稿只按 vanilla 理论排序；§〇·一 补入实测后，**「模组事件链降本」从后台升到 P0**（生产服实测第一大热点），并保留 vanilla 结构性优化为长期纵深。表格分两段：**A. 实测瓶颈优先** / **B. vanilla 结构性热点**。

### A. 实测瓶颈优先（生产服 Spark 实测驱动，状态见 `techdoc (1).html` §1）

| 优先级 | 缺口 | 实测链路 | 收益 | 兼容风险 | 建议 |
|---|---|---|---|---|---|
| **P0** | **削模组事件洪流的内部成本** | `EventBus.post` 子树 **75.5%**；`AdvQuarryEntity.breakBlocks` 整列破块每块发 `BlockEvent`；`DestructionTaskState.copyTags` 事件内 NBT 拷贝 | **高（当前生产第一大热点）** | **中**（事件**必须照发**，只降派发/锁/NBT 拷贝内部成本，绝不动事件数量/时机） | **做**（详见 §五①） |
| **P0** | **生产减压：邻居更新阈值回落 + 开可靠保存** | `max-chained-neighbor-updates=1000000`（过松）→ 16/≤1000；`reliable-chunk-save` | 中 | 低 | **做**（techdoc §14.3 已列，属配置收敛非新机制） |

### B. vanilla 结构性热点（真实但非当前边际瓶颈，长期纵深）

| 优先级 | 缺口 | 原版烂在哪 | 收益 | 兼容风险 | 建议 |
|---|---|---|---|---|---|
| ~~P0~~ | **光照更新预算化** | 单 tick 光照风暴卡死主线程 | 高（机械/红石服主痛点） | **极低**（语义不变，仅延迟） | ✅ **已实现**（`79406ea4`，剩调阈值） |
| **P0** | **漏斗空转检测** | 无物可动的漏斗每 tick 满频探测 | 高（农场服） | 低（有物即恢复，可关） | **做** |
| ~~P1~~ | **contiguous 不透明短路** | 不透明方块互替仍走光照 | 中 | 低（每次调方法，不缓存） | **取消**（1.21.1 冗余，见 §3.5 更正） |
| **P1** | **未变区块跳过存盘** | 全量 NBT 每存盘 tick 重写 | 中高（IO 尖峰） | 中（需保全量语义） | 研究 |
| **P2** | **红石 dust power 幂等短路** | power 未变仍重算广播 | 中 | 高（红石最敏感） | 研究，默认关 |
| **P2** | **实体 AABB 空间索引** | section 线性扫描 | 中 | 中（返回顺序） | 研究，只加速纯查询 |
| **P3** | **活塞批量原子化** | 逐 setBlock 中间态级联 | 中 | **高**（mod 机械核心） | **暂不做** |

### 与 `techdoc (1).html` §14.3「未来演进」的衔接（避免重复立项）

HTML 已登记的未来演进：`N=16`、不等宽条带、完整数据副本（RegionLevel 权威副本）、BE 并行灰度（`item_vault` 单点起测）、Create 轨道感知（chunk-blocks 64→32/16）、客户端 `TrainMapRenderer` NPE、生产减压。**这些是「多线程引擎自身」的演进**，与本文「vanilla 烂操作优化」互补不冲突；`N=16`/不等宽条带/完整副本属 P3 区域化的纵深，不在本文 vanilla 优化范围内，本文不再重复展开。

---

## 四、第三方模组主动兼容原则（全文贯穿的红线）

本项目所有优化必须遵守以下五条，这是「我们兼容模组、而非模组兼容我们」的具体落地：

1. **检测到他人优化器，主动让位**：`@LoadIfMod(ModCondition.ABSENT)`——Lithium/Canary/Radium/Recruits 已做同类优化时，我们的 `ClassInstanceMultiMap` 优化不生效，避免「双优化」冲突。**光照优化已落实让位**（`LightEngineMixin_LightBudget` 对 C2ME/Lithium/Canary/Radium ABSENT，`79406ea4`）；**未来的实体空间索引优化必须同样让位给 C2ME/Lithium 等**。

2. **检测到 mod 破坏并行语义，主动降级串行**：`serializeBlockTicksForMods()` 对 `alternate_current` 回退。这是「宁可牺牲性能，不可破坏 mod」的底线。

3. **mod 专属行为，定向路由而非全局下重手**：`be-main-thread-force`（`create:track`）、`main-thread-entity-force`（`minecolonies`）、`TeslaCoilMixin`（`ae2lt`）——对已知 mod 的尖峰依赖做窄范围处理，不影响其他 mod。

4. **未见过的新 mod，默认回退 vanilla**：`thread-policy: stats/enforce/off`，worker 违规默认只统计不拦截；`main-thread-routing: auto` 用违规学习台账（`ClassAffinityLedger`）把「实际踩到主线程依赖的 mod 实体」在运行期自动路由，而不是靠猜。

5. **每一个优化都有配置逃逸阀**：`prts-features.yml` / `servercore.yml` 全部可关，`enabled: false` 整体回退原版。**任何新优化（尤其光照、漏斗、红石）必须默认可关、最好默认保守**。

---

## 五、对「下一步高负载优化」的具体建议顺序

在不动摇兼容底线的优先序下，建议按如下推进（每项都先做「压测 + 默认关 + 可配」最小闭环）。**顺序已按 §〇·一 实测瓶颈重排：模组事件链降本在前，vanilla 结构性优化在后。**

1. **削模组事件洪流的内部成本**（P0，**实测第一大热点**）。目标不是少发事件，而是**让每个 `EventBus.post` 更便宜**：
   - `BlockEvent` 派发路径：`EventBus.post` 的多态派发 + `HashMap` 查找（`ListenerList`/`EventBusData`）+ 锁竞争。可做**缓存 post 目标**（按事件类型 + 监听器集合快照，避免每事件重查），但**必须每次验证监听器集合未变**（mod 可动态注册/注销监听器）。
   - `AdvQuarryEntity.breakBlocks` 整列破块每块发 `BlockEvent`：**不能吞事件**（QuarryPlus 等依赖它），但可缓存「破块时重复读取的 `getBlockState`/方块的 `getLightBlock`/NBT」，把「一列 N 块」的重复读降为每块一次。
   - `DestructionTaskState.copyTags` 事件内 NBT 拷贝：识别「事件监听器不需要可变 NBT 副本」的场景，用只读视图/懒拷贝替代深拷贝。**兼容点**：NBT 是 mod 公开数据，任何懒拷贝必须保证「mod 写入后仍正常」（写时拷贝）。
   - 先做 `grep` 归因：用现有 `AsyncTaskStats`/spark 复测，确认 `EventBus.post` 子树内到底哪一层可降（派发 vs 锁 vs 拷贝），再选点。
   - **红线**：`BlockEvent` 的数量与时机是 mod 契约，**绝不削减/延迟/合并**；只降单个事件的内部成本。

2. **生产减压（配置收敛）**（P0）。`max-chained-neighbor-updates=1000000 → 16/≤1000`、开 `reliable-chunk-save`。techdoc §14.3 已列，属「把已上线机制调回正确档位」，非新机制、无新风险。

3. **光照更新预算化**（P0，vanilla 结构性里风险最低）。在 LightEngine 传播队列加每 tick 预算，语义不变。先 `stats` 观测队列长度，再决定阈值。**✅ 已实现（`79406ea4`）**：剩余动作是按 `[light-engine]` 遥测把 `lighting.budget-per-tick` 从保守默认 100000 调到实测基准。

4. **漏斗空转检测**（P0）。只对「上一 tick 无传输成功」的漏斗降频，有物即恢复。默认开但可关。

5. ~~**不透明方块光照短路**（P1）~~。`getLightEmission==0 && 两侧 getLightBlock==15` 时跳过 `checkBlock`；每次现调两个方法，不碰 mod 覆写语义。**已取消**：1.21.1 上 vanilla `hasDifferentLightProperties` 已内置等价短路（见 §3.5 方案2 更正）。

6. **未变区块跳过存盘**（P1，研究）。最保守做「dirty=false 区块不重写」，保全量语义。

7. **红石 power 幂等短路**（P2，研究）。检测到红石增强 mod 整体让位，默认关。

8. **实体 AABB 空间索引**（P2，研究）。只加速纯空间查询，玩家交互路径保留线性扫描。

9. **活塞批量原子化**（P3，暂不做）。列为研究项，除非实测活塞成为瓶颈。

---

## 六、验证建议

- 每个优化都走「prts-features.yml 默认关 → stats 观测 → 实机开 → 回归」闭环。
- **事件洪流降本**：复用 spark 复测 `EventBus.post` 子树（目标从 75.5% 显著回落），并做**功能回归**——QuarryPlus 整列破块、RTSbuilding 破坏任务、其他监听 `BlockEvent` 的 mod 必须**一个事件都不少**（可加监听器计数断言，确认事件数量/时机零变化）。
- 光照预算化（`79406ea4` 已实现）：用 `[light-engine]` 遥测日志（`updates= queue= run=avg/max`，AsyncTaskStats）观测队列长度与单次排空耗时，确认风暴时预算让 `updates` 分摊到多个 tick、`run max` 回落，且最终光照一致（无「黑块」/「光错」）；再据此调 `lighting.budget-per-tick`。
- 漏斗降频：用 `BlockEntityTickStats` 对比开关前后漏斗 tick 次数与 mspt，确认无物品时数量显著下降、有物品时恢复。
- 所有新优化叠加「第三方 mod 压测」：Create 机械、AE2 网络、红石工程、殖民地 NPC 同时跑，确认无线程违规（`thread-policy: enforce` 测服定位）/ 无功能回退。

---

## 七、结论

原版 Minecraft 服务端的「烂」集中在**单线程换代 + 无预算链式反应**。PRTS 已通过维度/区域并行、异步寻路、异步 IO、统一区块需求，把**主循环换代**这条最粗的腿搬空。

但重读 `docs/PRTS-multithreading-techdoc (1).html` §1 后，本结论需**修正一处**：生产服 Spark 实测证明，多线程搬空世界计算后，**实测第一大瓶颈不是任何 vanilla 热点，而是模组事件洪流**——`EventBus.post` 子树 75.5%、`AdvQuarryEntity.breakBlocks` 整列破块每块发 `BlockEvent`、`DestructionTaskState.copyTags` 事件内 NBT 拷贝。PRTS 核心引擎税仅 2.56%。

因此下一步的优先级顺序是：

1. **削模组事件洪流的内部成本**（P0）——事件照发，只降派发/锁/NBT 拷贝成本，这是当前生产服边际收益最大的一处。
2. **生产减压**（`max-chained-neighbor-updates` 回落 + 开 `reliable-chunk-save`，techdoc §14.3 已列）。
3. **光照预算化（✅ 已实现，`79406ea4`）+ 漏斗空转检测（待做）**（vanilla 结构性热点里最低风险、最高收益）作为长期纵深。

两类优化共同点仍是：**无法靠并行搬走，只能靠「削减无谓重算 + 预算化」根治**——而这恰好是兼容第三方模组最友好的形态（语义不变、最终一致、可配置回退）。所有项始终守住「**我们兼容第三方模组、而非模组兼容我们**」这条红线，尤其对 `BlockEvent`：**数量与时机是 mod 契约，绝不削减，只降单个事件的内部成本**。