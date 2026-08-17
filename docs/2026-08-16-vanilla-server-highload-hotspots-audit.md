# 原版 Minecraft 服务端「烂操作」高负载审计与兼容安全优化方向

> **文档性质**：AI 创作（针对 PRTS 服务端 1.21.1 多线程引擎的再审计，聚焦「原版服务端本身就很烂的高负载计算」）。
> **审查基线**：分支 `1.21.1-Multithreading`，HEAD `539c1260`（fix: isolate block-entity worker tick failures）。
> **审查范围**：`arclight-common` 的 `optimization/general/servercore`（维度并行 / 区域并行 / 异步寻路 / 异步 IO / 统一区块需求）与 `optimization/general`（追踪 / 邻居 / 网络 / 随机 tick / 形状）。
> **文档目的**：只分析、只给方向，**不写代码**。逐一指出原版服务端哪些操作天然低效、当前已优化到哪一步、剩余缺口有哪些、以及**在保证「我们主动兼容第三方模组、而非让模组反过来兼容我们」前提下的优化策略**。
>
> **⚠️ 本文档初稿为「纯理论 vanilla 热点审计」；2026-08-16 重读 `docs/PRTS-multithreading-techdoc (1).html` §1 后补入实测瓶颈（见 §〇·一），并据此重排优先级。核心结论修正：生产服实测第一大瓶颈不是任何 vanilla 热点，而是模组事件洪流 `EventBus.post`。**
>
> **📌 2026-08-16 二刷追加**：补充审计「阶段 5」（见 §二·阶段5）——前文未覆盖的**「反复扫描型」原版烂操作**：POI 查询线性扫描（P1）、移动碰撞三轴三查（P1）、typed 实体查询未桶化（P2，entityspatial 二期）、位置相关形状不缓存（P2）、GameEvent 派发链（P2，实测归因）、容器菜单全槽广播 / Tab list 广播 / 弹射物 clip（P3）。均已对照 1.21.1 源码核实，并同步进 §三 缺口汇总表与 §五 建议顺序。

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
| 16 | **实体 AABB 空间查询** | ◐ 已部分优化 | `entityspatial/*`（懒 4×4×4 子格索引，默认开；typed 查询不动） |
| 17 | **活塞批量移动** | ◐ 仅激活范围修复 | 无批量移动优化 |
| 18 | **液体流动** | ◐ 仅随机 tick | 无流动批量优化 |
| 19 | **区块保存全量 NBT** | ◐ 仅 WAL + 异步 IO | 无序列化减负 |
| 20 | **红石 dust power 重算** | ◐ 仅熔断 | 无重算风暴根治 |
| 21 | **POI 查询** | ✅ 已优化 | 空 chunk 存在性预检 + present 掩码（`poi/*`，见 §阶段5·5.1） |
| 22 | **实体移动碰撞** | ✅ 已优化 | step-up 二次收集去重（`collision/*`，见 §阶段5·5.2） |
| 23 | **typed 实体查询** | ◐ 未优化 | entityspatial 仅加速无类型查询（见 §阶段5·5.4） |
| 24 | **位置相关方块形状** | ◐ 未优化 | 无 per-chunk 缓存（见 §阶段5·5.3） |

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

#### 1.3 实体碰撞检测与 `getEntities(AABB)` 空间查询 ⭐缺口 → 已部分优化（懒 4×4×4 子格索引，默认开）
- **原版机理**：`Entity.getBoundingBox` 与 `Entity.getCollisions`；`Level.getEntities(Entity, AABB)` 通过 `EntitySectionStorage` 把 AABB 落进 `EntitySection` 后**线性遍历该 section 的所有实体做盒-盒相交**。原版对「某区域有哪些实体」没有真正的空间索引，section 内是裸 `List`。
- **为什么烂**：高密度场景（刷怪塔、养鸡场、掉落物山、末影龙战）下，一次 `getEntities(AABB)` 就是 O(该 section 实体数) 的线性扫描，且**每次 tick 被反复调用**（AI 探测、碰撞、玩家交互、`item.merge` 等）。
- **已优化**：① `RegionTickManager` 施加 section 级锁（`EntitySectionMixin_RegionLock`）保证并行下不崩；② **✅ 懒 4×4×4 子格空间索引**（`entityspatial/*`，本节下文），section 实体数 ≥ `entity-spatial-index.min-section-size`（默认 16）时建索引，纯空间 `getEntities(AABB)` 只扫命中子格。
- **兼容安全优化方向（已按此实现）**：
  - **给 section 内实体加懒散空间/类型索引**（如按 `MobCategory` 或按小网格分桶），把「扫描全 section」改为「只扫命中网格」。**关键兼容点**：必须保留 `getEntities` 的**返回语义**（含顺序、去重、是否含自身），且对 mod 自定义实体类型**不区分**（索引只按原版已有属性分桶，不按类名特判）。检测到 Lithium/Canary 等已做此优化的 mod 时整体让位（`@LoadIfMod ABSENT`）。
  - **风险**：返回顺序若被 mod 依赖（如拾取顺序、AI 选目标顺序），分桶会改变结果。建议**只对「无玩家交互的纯空间查询」加速**，玩家交互路径保留原版线性扫描。
  - **✅ 已实现（`2026-08-16`，`entityspatial/*`）**：`EntitySpatialIndex`（64 桶，按 **bb 中心**低 4 位单桶归属）+ `EntitySectionMixin_SpatialIndex`（查询 HEAD 走索引、add/remove RETURN 维护、懒构建回填）+ `EntityMixin_SectionIndexRebome`（`setBoundingBox` TAIL，section 内移动 rehome——`bb` 字段唯一运行时写入点）。**保序**：实体加插入序号，命中候选按序号排序输出，与原版 per-section 顺序（`allInstances` 插入序）完全一致（`ArrayList.remove` 保序 ⇒ 剩余实体顺序 = 插入序）；**不漏**：桶按 bb 中心归属 ⇒ 实体 bb 与查询框相交 ⇒ 中心距查询框 ≤ bb 半径（最大 4，史莱姆 8×8×8），查询框统一膨胀 4 格覆盖中心桶（真机遥测曾用 8 格导致候选≈全扫，已修正）。锁与 `EntitySectionMixin_RegionLock` 共用 `arclight$sectionLock`（经 `ISectionLock` bridge）。**默认开**（`entity-spatial-index.enabled: true`，用户 2026-08-16 决策），配 `[entity-spatial-index]` 遥测；typed 查询已由二期（§5.4）覆盖、玩家交互路径 / `getNearbyPlayers`（本就不走 section）不动。真机验证（`2026-08-16` 测试服，120 只 AI 僵尸高密度 A/B 对比）：**索引开 avg mspt 5.1→3.4ms（-33%）**、TPS 20.0、0 异常；40 只场景（AI 35 格大框查询主导）索引无收益也无损失。**残余兼容风险**：超大 bb 实体（半径 > 4，vanilla 无；mod 自定义超大实体罕见）被「只覆盖其部分 bb 的小框查询」命中时理论上可能漏检——vanilla 线性扫描无此问题；若生产服出现可疑行为，先关 `entity-spatial-index.enabled` 回归确认。

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

### 阶段 5：补充审计——「反复扫描型」原版烂操作（2026-08-16 追加）

> **追加说明**：§二 前四阶段覆盖了主循环的「换代」主体（实体/方块/tick 链），阶段 5 补的是另一类原版结构性烂操作——**「每次查询都全量扫、且被高频调用」**。它们不依赖单线程换代，无法靠并行搬走，只能靠「索引 + 削减无谓重算」根治；与全文红线一致：语义不变、可配置回退、默认保守。以下各项均已对照 1.21.1 源码核实（方法名/调用链），优先级已并入 §三 汇总表。

#### 5.1 POI 查询线性扫描 ⭐P1（高收益-低风险）→ ✅ **已实现**（2026-08-16，`poi/*`）
- **原版机理（1.21.1 实况，已对照源码核实）**：`PoiSection` **已经**按 `PoiType` 分桶（`Map<Holder<PoiType>, Set<PoiRecord>> byType`，`getRecords` 只扫命中类型的桶）——**初稿「裸 Map 全扫」对 1.21.1 不成立，已修正**。真正剩余的成本在 `PoiManager.getInChunk`：对查询方阵内**每一个 chunk** 执行 `IntStream.range(minSection, maxSection)` → 逐 section `getOrLoad` + `Optional` 包装——村庄稀疏分布下，**绝大多数 chunk 根本没有 POI section**，一次 `getInRange` 就是对 25 个 chunk × 24~32 个垂直 section 的空扫描（每查询约 700 次 `getOrLoad` 调用链）。另有 `findClosest` 对范围内全部候选 `.min()` 无剪枝（本期未做，见下）。
- **为什么烂**：村民 `AcquirePoi` / `WalkToPoi`（认领职业、回家、集合点）每 5–20 tick 一次；蜜蜂找巢、铁傀儡生成、`take` 占用全走同一条链。村庄人口多时 = **O(村民数 × 范围 chunk 数 × 垂直 section 数)** 的重复空扫描，且每 tick 反复执行。
- **已优化**：① `PoiManagerMixin_PoiLock`（区域并行下 DistanceTracker 加锁）；② **✅ `poi/*`（本次落地）**：`SectionStorageMixin_Presence` 维护「chunk → present section y 位掩码」（`getOrLoad`/`getOrCreate` RETURN 时单调累加——1.21.1 `SectionStorage` 的 section 从不卸载，掩码精确无残留），`PoiManagerMixin_QueryFastPath` 在 `getInChunk` HEAD 走快速路径：**已知空 chunk 直接返回空流（跳过整个垂直扫描）**、有 POI 的 chunk 只迭代 present 位对应的 y 层、**从未读盘的冷 chunk 保持原版 `getOrLoad` 路径**（含同步读盘，磁盘 POI 不漏）。配置 `poi-query.enabled`（默认开）+ `[poi-query]` 遥测（`indexedChunks`/`skippedEmptyChunks`/`vanillaChunks`）。真机验证（2026-08-16 测试服）：村民触发查询后 30 秒窗口内 **`skippedEmptyChunks=12288`**（1.2 万空 chunk 跳过垂直扫描）、`indexedChunks=154`、`vanillaChunks=32`（冷 chunk 走原版读盘），TPS 19.9 稳定、0 异常。
- **兼容点**：语义零变化（跳过空 chunk = 结果集不变；present 位迭代与原版 y 升序一致；冷 chunk 原版语义保留）。不依赖谓词形态（`getRecords(predicate, occupancy)` 原样调用），mod 自定义谓词不受影响。无已知同类优化器，不需要让位。
- **剩余缺口（二期候选）**：`findClosest` / `take` 的「最近优先剪枝」（按 chunk 距查询中心升序迭代 + 提前终止）——在同类型 POI 密集（大量农田）时收益显著，本期未做。风险点：与原版的「并列距离取序」差异（原版本身是 HashSet 序，非契约）。

#### 5.2 实体移动碰撞「上台阶二次全量收集」 ⭐P1（纯读优化）→ ✅ **已实现**（2026-08-16，`collision/EntityMixin_CollisionBatch`）
- **原版机理（1.21.1 实况，已对照源码核实）**：**初稿「三轴三查」对 1.21.1 不成立，已修正**——`collideBoundingBox` 已经是「**一次收集、逐轴 clip**」：`collectColliders` 把扫掠区域的全部方块形状一次性取进 `List<VoxelShape>`（`world.getBlockCollisions`），`collideWithShapes` 再对已取列表做三轴 clip。**真正的剩余成本在 `Entity.collide` 的 step-up 上台阶分支**（line 947）：`maxUpStep > 0` 且地面移动时，对**扩展区域（升高 maxUpStep）再次执行全量 `collectColliders`**——与第一次收集重叠的方块全部重取，而走路生物每次地面移动都付这个二重成本。
- **为什么烂**：碰撞是 `Entity.move` 的固定成本，每个 tick 每个移动实体都付；step-up 分支对几乎所有地面步行生物（`maxUpStep > 0` + 水平位移）必然触发 → **每次地面移动 2 次全量区域取形状**。§1.1 说「碰撞子阶段是实体 tick 最贵部分」，此即其具体构成之一。
- **已优化**：① §1.3 实体空间索引只加速「实体-实体」；② **✅ `collision/EntityMixin_CollisionBatch`（本次落地）**：`collide` HEAD 初始化 per-entity 帧级缓存，`collectColliders` 两处调用点 @Redirect 到批处理逻辑——第一次收集（swept 区域）入缓存；step-up 请求满足「水平范围相同 + 仅向上扩展」时**只增量补取顶部条带**（`level.getBlockCollisions(cap)`），合并后与全量结果**形状集完全一致**；区域不匹配/重入帧回退原版收集。纯读、语义零变化（每轴 clip 与候选台阶高度从相同形状集计算）。配置 `collision-batch.enabled`（默认开）+ `[collision-batch]` 遥测（`reusedShapes`/`incrementalFetches`/`fullFetches`）；**对 Lithium/Canary/Radium 让位**（它们重写同一条碰撞路径）。真机验证（2026-08-16 测试服）：`incrementalFetches` 持续增长（step-up 增量补取在工作），TPS 19.9 稳定、0 异常。
- **兼容点**：① `getCollisionShape` 仍逐格调用（mod 覆写语义保留）——只是 step-up 的重叠区从「全量重取」降为「缓存复用」；② 缓存只在单个 `collide` 帧内存在（`Entity` 实例字段，HEAD 建 RETURN 清），mod 直接调用静态 `collideBoundingBox` 时无缓存 → 原版路径；③ 与区域并行无冲突（纯读，worker 各自实体实例）。
- **剩余缺口**：`collideWithShapes` 每轴对列表的遍历是纯算术（已是最优形态）；位置相关形状（栅栏/墙）的缓存见 5.3（P2 研究）。

#### 5.3 位置相关方块形状不缓存 ⭐P2（研究）
- **原版机理**：`FenceBlock` / `WallBlock` / `PipeBlock`（玻璃板、铁栏杆）/ `MultifaceBlock`（发光地衣等）的 `getCollisionShape(level, pos)` **每次调用重算 4 向邻居连接**（每次 4 次 `getBlockState` + 分支拼接）；原版只按 `BlockState` 缓存与位置无关的形状（`CollisionShapeCache`），位置相关形状**零缓存**。
- **为什么烂**：密集栅栏/玻璃墙/铁栏杆区域（动物圈、温室、大型建筑）里，5.2 的每轴碰撞都触发重算——一次移动 × 最多 3 轴 × 27 格，连接型方块每格重算 4 次邻居读；且这类场景常与「生物聚堆」叠加。
- **兼容安全优化方向**：**per-chunk 位置形状缓存**：`LevelChunk` 上挂 `Map<BlockPos, VoxelShape>`（LRU 上限），命中即返回；`setBlockState` 时失效目标格 ±2 半径（连接型方块只依赖 ±1 邻位，±2 是安全余量）。**兼容点**：① 只缓存明确实现位置分支的方块（按类名/继承白名单：Fence/Wall/Pipe/Multiface + 子树），mod 自定义方块默认 miss；② 失效必须精确（±2 内任何 setBlockState 都清对应缓存），否则出现「墙接了新栅栏但碰撞形状没跟上」的错位；③ 检测「修改形状的 mod」困难——本项最大风险，故默认关。
- **优先级**：**P2（研究）**。收益中（密集连接型方块场景），风险中（失效正确性）。

#### 5.4 typed 实体查询（`getEntitiesOfClass`）未桶化 ⭐P2（entityspatial 二期）→ ✅ **已实现**（2026-08-16 晚，`entityspatial/*` 二期）
- **原版机理**：`entityspatial/*` 索引只加速**无类型** `getEntities(Entity, AABB)`；`getEntitiesOfClass` 仍走 `EntitySection.getEntitiesOfClass` → `ClassInstanceMultiMap` 的类列表线性扫描（§1.4 的惰性 byClass 只是去掉了「空类索引」的预建成本，**扫描本身没变**）。
- **为什么烂**：typed 查询是全服最频繁的实体空间查询形态——村民 Brain 传感器（`NearestLivingEntitySensor` 每 tick `getEntitiesOfClass(LivingEntity.class, box)`）、僵尸增援（`getEntitiesOfClass(Zombie.class)`）、Allay 找物品、Raid 扫掠者清点、刷怪笼上限检查（`BaseSpawner`）、`Level.findNearestEntity`。高密度区每次都是 O(同 section 同类实体数)，且每 tick 反复调用。
- **✅ 已实现（`entityspatial/*` 二期，落地记录见 `docs/2026-08-16-entityspatial-p2-typed-query-ab.md`）**：`EntitySpatialIndex` 新增 typed `query(EntityTypeTest, AABB, consumer, classCollection)` + `EntitySectionMixin_SpatialIndex` 的 typed HEAD 注入（`storage.find(baseClass)` 走 section **写锁**——与 `EntitySectionMixin_RegionLock` 原版 redirect 同纪律；查询走读锁）。实现与本节「类 × 子格复合桶」sketch 的偏差及理由：**改为在 vanilla 类列表上做覆盖格子预筛**（`isCellCovered`：成员 bb 中心所在格不在查询框(膨胀 4)覆盖格内则跳过）——语义逐位一致（顺序 = 原版类列表顺序；tryCast 逐成员执行，`forExactClass` 正确；`find` 的惰性构建与 `IllegalArgumentException` 保留），零漏检（同 §1.3 膨胀论证），且避免双层桶的 add/remove/rebome 维护成本。带自定义 `Predicate` 的查询在 Level 层 consumer 包装之下，语义零变化。**顺带修复一期 untyped 路径在「密集单 section 大框查询」几何下的性能倒挂**（gather+sort 白付）：三档路径（覆盖格 ≥32 → 纯 vanilla 循环；≥8 → storage 迭代 + 格预筛；<8 → 桶 gather + 排序）。
- **兼容点与 §1.3 完全一致**：Lithium/Canary/Radium ABSENT 让位（同一 mixin 类）、超大 bb 实体残余风险、保序（typed = 原版类列表顺序）；另加——谓词语义零变化（Level 层包装）、`forExactClass` 逐成员 tryCast 保留（`BaseSpawner` 用 exact-class，实测剪枝生效）。
- **真机验证**（2026-08-16 晚测试服，120 AI 僵尸围栏 pen，控制变量协议：永久午夜 + 关自然刷怪 + 关挤压 + 同流程 A/B）：索引开 vs 关 **avg mspt 2.0ms vs 1.9ms（持平，噪声内）**，TPS 20.0、0 异常；typed 遥测 `typedQueries≈155/tick`、小框查询场景（刷怪笼 9×9×9）`typedSkipped≈74/tick` 剪枝生效；大框查询（索敌/传感器）`typedSkipped=0` 属设计内 break-even（每成员 ~5ns 格检查，~57 成员/tick 可忽略）。**注意**：§1.3 一期真机 -33%（5.1→3.4ms）来自 08-16 白天不同环境与几何，与本次数字不可直接对比。

#### 5.5 GameEvent（游戏事件）派发链 ⭐P2（先实测归因）
- **原版机理**：每个 `Entity.move`（STEP 等）、每次 `setBlock` 都会走 `ServerLevel.gameEvent(Holder<GameEvent>, Vec3, GameEvent.Context)` → `GameEventDispatcher.post`；1.21.1 已改为队列批量处理（`handleGameEventMessagesInQueue`）。监听器（sculk 传感器、`DynamicGameEventListener`）存在时，每次事件要构造 `GameEvent.Context`（含状态/来源实体）并走监听器判定；**无监听器时近乎零成本**（空列表短路）。
- **为什么烂**：**成本与「有没有 sculk」强相关**——无 sculk 的服近零；有振动监听器的区域，`setBlock` 风暴与实体移动每次都要付派发成本。且 `setBlock` 路径上的 gameEvent 与 §〇·一 的 `BlockEvent` 洪流**同源**（都是方块变更的观测链），可合并归因。
- **兼容安全优化方向**：① **无监听器短路**（若 1.21.1 已短路则跳过）；② 监听器存在时的**距离粗筛**（监听范围外的事件直接丢弃——须与 `DynamicGameEventListener` 移入/移出语义一致，按「监听器当前位置 + 最大监听范围」计算）；③ 与 P0 事件洪流共享归因（spark 看 `GameEventDispatcher.post` / `handleGameEventMessagesInQueue` 子树）。
- **兼容点**：sculk 机制是 vanilla 契约（含 mod 扩展），事件**必须照发、时机不变**，只降内部成本。
- **优先级**：**P2（先 spark 归因再定）**。生产服无 sculk 密集区则跳过。

#### 5.6 容器菜单每 tick 全槽位广播（P3，先实测归因）
- **原版机理**：`AbstractContainerMenu.broadcastChanges` 对**每个打开中的菜单每 tick** 遍历全部槽位（`Slot.getItem` + `ItemStack.matches` 比较 + 变化槽发包）。
- **为什么烂**：已打开菜单数 × 槽位数是固定支出；AE2 终端、Create 背包、模组大容器（几百槽）打开时每 tick 全扫。变化槽发包本身是 diff 的（原版已做），但**「全槽位遍历 + 比较」不做 diff**。
- **兼容安全优化方向**：脏槽位跟踪（槽位变更时标记，`broadcastChanges` 只扫脏槽）或降频（2–4 tick 一次）。**兼容点**：mod 依赖精确同步（快速取放时序），降频有风险；脏槽跟踪语义等价，但要覆盖 mod 直接写容器（`Container.setItem` 绕过 menu）的路径——**先实测归因**，若生产服打开菜单数不多则跳过。
- **优先级**：**P3**。

#### 5.7 Tab list 每 tick 广播（P3，低优先，需实测）
- **原版机理**：1.20.1 的 `ServerPlayer.updateTabList` 每 tick 把本玩家行广播给全服玩家 → **O(玩家²)/tick** 的 `ClientboundPlayerInfoUpdatePacket`；1.21.1 玩家信息包结构已重做（`ClientboundPlayerInfoUpdatePacket` + `PlayerList.broadcastAll`），是否仍每 tick 广播**需实测确认**。
- **为什么烂**：13 人边际（≈169 行/tick）；100+ 人服务器显著（万行级/tick 的序列化 + 发包）。
- **兼容安全优化方向**：节流/按需（显示名变化、加入退出时立即，静态期降频）。低优先，先实测。

#### 5.8 弹射物射线 `Level.clip` 逐格查询（P3，研究）
- **原版机理**：`Projectile.tick` 每 tick 一次 `Level.clip`（`BlockGetter.clip` 沿射线逐格 `getBlockState` + `getCollisionShape().clip()`）；箭雨塔、弹药密集场景每 tick 数百次逐格查询。
- **兼容安全优化方向**：高密度场景节流/预算，或 shape clip 快速路径。收益边际，研究项。

#### 5.9 待实测候选池（先 spark 归因，不立项）
- `EntityTrackerEntry` 每 tick 的 culling AABB 分配（`getBoundingBoxForCulling`）——零分配主题的小项；
- 计分板系统（`ServerScoreboard` 每分数变更广播）——插件/模组计分场景；
- 信标金字塔扫描（每 80 tick 全金字塔 `getBlockState`）——边际。

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
| ~~P2~~ | **实体 AABB 空间索引** | section 线性扫描 | 中 | 中（返回顺序） | ✅ **已实现**（`entityspatial/*`，**默认开**，保序；实测 -33% avg mspt） |
| **P3** | **活塞批量原子化** | 逐 setBlock 中间态级联 | 中 | **高**（mod 机械核心） | **暂不做** |

**2026-08-16 二刷追加**（阶段 5，均对照 1.21.1 源码核实）：

| 优先级 | 缺口 | 原版烂在哪 | 收益 | 兼容风险 | 建议 |
|---|---|---|---|---|---|
| ~~P1~~ | **POI 查询空 chunk 扫描** | `getInChunk` 对查询方阵内每个 chunk 做 24~32 个垂直 section 空扫描（1.21.1 已按类型分桶，初稿论断已修正） | 高（村庄服） | 低（跳过空 chunk = 结果集不变；冷 chunk 保留原版读盘） | ✅ **已实现**（`poi/*`，默认开；实测 30s 跳过 12288 空 chunk） |
| ~~P1~~ | **移动碰撞 step-up 二次收集** | `Entity.collide` 上台阶分支对扩展区域全量重取（1.21.1 已一次收集，初稿「三轴三查」已修正） | 高（所有地面移动） | 低（纯读，缓存帧内有效，语义零变化） | ✅ **已实现**（`collision/*`，默认开，对 Lithium/Canary/Radium 让位） |
| ~~P2~~ | **typed 查询未桶化** | `getEntitiesOfClass` 线性扫同类实体（传感器/增援/刷怪笼） | 中（刷怪塔/村庄） | 中（同 entityspatial，保序/让位机制可复用） | ✅ **已实现**（entityspatial 二期，默认开；真机：大框持平、小框 typedSkipped 剪枝生效；落地见 `2026-08-16-entityspatial-p2-typed-query-ab.md`） |
| **P2** | **位置相关形状不缓存** | 栅栏/墙/玻璃板每碰重算 4 向邻居连接 | 中 | 中（失效精确性） | 研究，默认关（§阶段5·5.3） |
| **P2** | **GameEvent 派发链** | setBlock/move 每次构造 Context 并走派发；有 sculk 监听器时成本线性 | 中（有 sculk 时） | 低（只降内部成本，事件照发） | 先实测归因（§阶段5·5.5） |
| ~~P3~~ | **容器菜单全槽广播** | `broadcastChanges` 每 tick 遍历所有打开菜单全部槽位 | 中（大容器模组） | 中（同步时序） | ✅ **已实现**（2026-08-16 晚，`menubroadcast/*` + `/prtsfeatures menubench`，默认关；全等预检短路 + 失败冷却，语义逐位一致、mod 直写容器零漏检；实测静止 +54%、稀疏 +46%、密集持平，见 `2026-08-16-P3-menu-broadcast-ab.md`） |
| ~~P3~~ | **Tab list 广播** | 1.20.1 O(玩家²)/tick；1.21.1 需实测 | 低（13 人边际） | 低 | ✅ **已实测归因**（1.21.1 已无每 tick 广播：`refreshTabListName` diff 短路 + `PlayerList.tick` 每 600 tick 才发 `UPDATE_LATENCY`，无需实现，见 P3 文档 §4.1） |
| ~~P3~~ | **弹射物 clip 逐格查询** | 箭雨塔每 tick 数百次逐格 raycast | 低-中 | 低 | ✅ **已实测归因**（800 箭 + JFR：0 样本命中 clip；air 已短路，无安全优化点，不立项，见 P3 文档 §4.2） |

### 与 `techdoc (1).html` §14.3「未来演进」的衔接（避免重复立项）

HTML 已登记的未来演进：`N=16`、不等宽条带、完整数据副本（RegionLevel 权威副本）、BE 并行灰度（`item_vault` 单点起测）、Create 轨道感知（chunk-blocks 64→32/16）、客户端 `TrainMapRenderer` NPE、生产减压。**这些是「多线程引擎自身」的演进**，与本文「vanilla 烂操作优化」互补不冲突；`N=16`/不等宽条带/完整副本属 P3 区域化的纵深，不在本文 vanilla 优化范围内，本文不再重复展开。

---

## 四、第三方模组主动兼容原则（全文贯穿的红线）

本项目所有优化必须遵守以下五条，这是「我们兼容模组、而非模组兼容我们」的具体落地：

1. **检测到他人优化器，主动让位**：`@LoadIfMod(ModCondition.ABSENT)`——Lithium/Canary/Radium/Recruits 已做同类优化时，我们的 `ClassInstanceMultiMap` 优化不生效，避免「双优化」冲突。**光照优化已落实让位**（`LightEngineMixin_LightBudget` 对 C2ME/Lithium/Canary/Radium ABSENT，`79406ea4`）；**实体空间索引优化已落实让位**（`EntitySectionMixin_SpatialIndex` / `EntityMixin_SectionIndexRebome` 对 Lithium/Canary/Radium/Recruits ABSENT）。

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

8. **实体 AABB 空间索引**（P2，研究）。只加速纯空间查询，玩家交互路径保留线性扫描。**✅ 已实现（`entityspatial/*`，默认开）**：真机 A/B 已验证高密度场景 -33% avg mspt；剩余动作是生产服观察 `[entity-spatial-index]` 遥测（`indexedQueries` vs `fallbackQueries`、`candidatesScanned` vs `fullScanned`），按需调 `min-section-size`，并对「超大 bb mod 实体」的可疑行为做回归（见 §1.3 残余风险）。

9. **活塞批量原子化**（P3，暂不做）。列为研究项，除非实测活塞成为瓶颈。

**2026-08-16 二刷追加**（对应 §阶段5，插在 4 之后、与 5~9 并列推进）：

10. **POI 查询空 chunk 预检**（P1）✅ **已实现**（`poi/*`，默认开）。1.21.1 的 `PoiSection` 已按 `PoiType` 分桶（初稿论断修正），真实缺口是 `getInChunk` 对无 POI chunk 的全垂直 section 扫描。落地：`SectionStorageMixin_Presence` 维护 chunk→present 位掩码（单调累加，section 不卸载故精确）+ `PoiManagerMixin_QueryFastPath` 空 chunk 直接跳过、present chunk 只迭代命中 y 层、冷 chunk 保留原版读盘。真机：30s 跳过 12288 空 chunk。**二期候选**：`findClosest` 最近优先剪枝（同类型 POI 密集时收益显著）。
11. **移动碰撞 step-up 二次收集去重**（P1）✅ **已实现**（`collision/EntityMixin_CollisionBatch`，默认开，对 Lithium/Canary/Radium 让位）。1.21.1 已「一次收集、逐轴 clip」（初稿「三轴三查」修正），真实缺口是 step-up 分支的二次全量 `collectColliders`。落地：per-entity 帧级缓存 + 顶部条带增量补取，语义零变化。验证：`[collision-batch]` 遥测 `incrementalFetches` 增长 + 实体移动回归（上下台阶、贴墙滑行、活塞推挤对照原版）。
12. **typed 查询桶化 = entityspatial 二期**（P2）✅ **已实现**（`entityspatial/*` 二期，默认开，2026-08-16 晚）。实现为「vanilla 类列表 + 覆盖格子预筛」（语义逐位一致、零维护成本），并顺带把一期 untyped 路径改成三档（纯 vanilla 循环 / storage 迭代+格预筛 / 桶 gather+排序），消除密集单 section 大框查询的性能倒挂（实测 2.0 vs 1.9ms 持平）。剩余动作：生产服观察 `[entity-spatial-index]` 遥测的 `typedSkipped` / `membersSkipped` / `vanillaOrderQueries`，按需调 `min-section-size`。
13. **其余研究项**：位置相关形状缓存（P2，默认关）、GameEvent 派发链（P2，先 spark 归因，无 sculk 则跳过）、容器菜单全槽广播（P3）✅ **已实现**（`menubroadcast/*`，默认关，2026-08-16 晚，见 `2026-08-16-P3-menu-broadcast-ab.md`）、Tab list 广播（P3）✅ **已实测归因取消**（1.21.1 无每 tick 广播）、弹射物 `Level.clip`（P3）✅ **已实测归因不立项**（800 箭 JFR 0 样本 + air 已短路）。全部走「默认关 → stats 观测 → 实机开 → 回归」闭环。

---

## 六、验证建议

- 每个优化都走「prts-features.yml 默认关 → stats 观测 → 实机开 → 回归」闭环。
- **事件洪流降本**：复用 spark 复测 `EventBus.post` 子树（目标从 75.5% 显著回落），并做**功能回归**——QuarryPlus 整列破块、RTSbuilding 破坏任务、其他监听 `BlockEvent` 的 mod 必须**一个事件都不少**（可加监听器计数断言，确认事件数量/时机零变化）。
- 光照预算化（`79406ea4` 已实现）：用 `[light-engine]` 遥测日志（`updates= queue= run=avg/max`，AsyncTaskStats）观测队列长度与单次排空耗时，确认风暴时预算让 `updates` 分摊到多个 tick、`run max` 回落，且最终光照一致（无「黑块」/「光错」）；再据此调 `lighting.budget-per-tick`。
- 漏斗降频：用 `BlockEntityTickStats` 对比开关前后漏斗 tick 次数与 mspt，确认无物品时数量显著下降、有物品时恢复。
- 实体空间索引（`entityspatial/*` 已实现，默认开）：高密度 section（刷怪塔/掉落物堆）压测，对比 `[entity-spatial-index]` 遥测 `candidatesScanned` vs `fullScanned`（期望 candidates < fullScanned 同口径）；回归物品合并/AI 目标/拾取**顺序**与原版一致（顺序断言）；`thread-policy: enforce` 测服下无线程违规；可疑行为先关 `entity-spatial-index.enabled` 回归确认（超大 bb mod 实体残余风险）。
- 所有新优化叠加「第三方 mod 压测」：Create 机械、AE2 网络、红石工程、殖民地 NPC 同时跑，确认无线程违规（`thread-policy: enforce` 测服定位）/ 无功能回退。

---

## 七、结论

原版 Minecraft 服务端的「烂」集中在**单线程换代 + 无预算链式反应**。PRTS 已通过维度/区域并行、异步寻路、异步 IO、统一区块需求，把**主循环换代**这条最粗的腿搬空。

但重读 `docs/PRTS-multithreading-techdoc (1).html` §1 后，本结论需**修正一处**：生产服 Spark 实测证明，多线程搬空世界计算后，**实测第一大瓶颈不是任何 vanilla 热点，而是模组事件洪流**——`EventBus.post` 子树 75.5%、`AdvQuarryEntity.breakBlocks` 整列破块每块发 `BlockEvent`、`DestructionTaskState.copyTags` 事件内 NBT 拷贝。PRTS 核心引擎税仅 2.56%。

因此下一步的优先级顺序是：

1. **削模组事件洪流的内部成本**（P0）——事件照发，只降派发/锁/NBT 拷贝成本，这是当前生产服边际收益最大的一处。
2. **生产减压**（`max-chained-neighbor-updates` 回落 + 开 `reliable-chunk-save`，techdoc §14.3 已列）。
3. **光照预算化（✅ 已实现，`79406ea4`）+ 漏斗空转检测（待做）**（vanilla 结构性热点里最低风险、最高收益）作为长期纵深；**实体 AABB 空间索引（✅ 已实现，`entityspatial/*`，默认关）** 属 P2 研究项，按 `[entity-spatial-index]` 遥测确认收益后再开。
4. **2026-08-16 二刷追加的「反复扫描型」两项 P1**：**POI 查询分桶**（村庄服高收益）与**移动碰撞批量取形状**（所有实体移动的纯读优化）——与第 3 项同属「削减无谓重算」家族，语义不变、可配置回退，插在光照/漏斗之后推进；**typed 查询桶化**作为 entityspatial 二期（P2）复用已验证的保序/让位/遥测机制；其余（位置形状缓存、GameEvent、容器广播、Tab list、clip）先 spark 归因再立项（详见 §阶段5）。

两类优化共同点仍是：**无法靠并行搬走，只能靠「削减无谓重算 + 预算化」根治**——而这恰好是兼容第三方模组最友好的形态（语义不变、最终一致、可配置回退）。所有项始终守住「**我们兼容第三方模组、而非模组兼容我们**」这条红线，尤其对 `BlockEvent`：**数量与时机是 mod 契约，绝不削减，只降单个事件的内部成本**。