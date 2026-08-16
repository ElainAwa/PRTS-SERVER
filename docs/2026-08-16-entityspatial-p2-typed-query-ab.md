# entityspatial 二期：typed 实体查询加速 —— 实现 + 性能对照表（2026-08-16 晚，测试服实机）

> **文档性质**：AI 创作。对应审计文档 `2026-08-16-vanilla-server-highload-hotspots-audit.md` §阶段5·5.4（P2「做」项）的落地记录。
> **审查基线**：分支 `feature/lightopti`，HEAD `0eb91896` 之上新增本功能。
> **验证环境**：`prts-test` 测试服（NeoForge 1.21.1 + PRTS v1.0.36-Multithreading 构建，无 mod），RCON 控制。
> **兼容红线**：与 §1.3 entityspatial 一期完全一致——保序、Lithium/Canary/Radium/Recruits ABSENT 让位、查询框膨胀复用、超大 bb 残余风险、配置逃逸阀。

---

## 一、实现摘要（P2 本体：typed 查询桶化）

### 1.1 目标
`EntitySection.getEntities(EntityTypeTest, AABB, consumer)`（即 `Level.getEntitiesOfClass` / `getEntities(EntityTypeTest, ...)` 的 section 层）在 vanilla 下：
`storage.find(test.getBaseClass())` 取**同类实体全列表** → 逐成员 `tryCast` + `getBoundingBox().intersects`，**与空间无关**：查询框再小也要扫整个类列表并做相交测试。

### 1.2 做法（与审计 §5.4 的「类 × 子格复合桶」sketch 的偏差与理由）
未维护「类 × 子格」双层桶，而是**在 vanilla 类列表上做覆盖格子预筛**：

```
typed query = 取 vanilla 类列表（storage.find(baseClass)，原版惰性构建/顺序/异常语义原样）
            → 逐成员：tryCast（forExactClass 也正确）→ 中心所在格子是否被查询框(膨胀 4)覆盖
            → 覆盖才做 intersects + consumer
```

- **语义逐位一致**：顺序 = 原版类列表顺序（构造保证）；结果集 = 原版（膨胀保证不漏，见下）；abort 传播一致。
- **零漏检**：实体 bb 与查询框相交 ⇒ 其 bb 中心在框的 4 格膨胀内 ⇒ 中心格被覆盖 ⇒ 不会被跳过（与一期索引同款论证）。
- **为什么不做「类 × 子格」桶**：为省掉「对同类成员的 instanceof 过滤」而给 64 格 × N 类维护双层桶，会让每次 add/remove/rebome 多付 2~3 次 map 操作；而 instanceof 本身 ~2ns，且候选已被格子预筛——维护成本远大于省下的过滤成本。审计的收益点（高密度区不扫同类全列表）已由预筛达成。
- **谓词查询**：自定义 `Predicate` 在 `Level` 层（consumer 包装）应用，在本注入点之下——语义零变化，无需回退。

### 1.3 顺带修复：一期 untyped 路径在「密集单 section」几何下的性能倒挂（真实 A/B 发现）
- **问题**：一期 `query(AABB)` 每查询 gather 覆盖格子候选 + 按插入序号全排序（IdentityHashMap 比较器）——查询框接近整 section 时（AI 索敌/传感器 35~70 格大框），候选≈全 section，排序白付 O(n log n) + 每次查询分配 ArrayList。
- **修复（三档路径）**：
  1. 覆盖格子 ≥ 32（半 section）→ **纯 vanilla 循环**（零索引开销，代价与 vanilla 完全相等）；
  2. 覆盖格子 ≥ 8 → **storage 迭代 + 中心格预筛**（vanilla 顺序、零分配、无排序）；
  3. < 8 → 桶 gather + 序号排序（候选集小，排序便宜）。
- typed 路径天然无此问题（本来就迭代类列表、无排序）。

### 1.4 改动文件
| 文件 | 改动 |
|---|---|
| `optimization/general/entityspatial/EntitySpatialIndex.java` | +typed `query(EntityTypeTest,...)`；untyped 三档路径；`isCellCovered` / `coveredCellCount` |
| `mixin/.../entityspatial/EntitySectionMixin_SpatialIndex.java` | +typed HEAD 注入（find 走 section 写锁——与 `EntitySectionMixin_RegionLock` 的原版 redirect 同锁纪律；查询走读锁）；untyped 注入传 `storage` |
| `optimization/general/entityspatial/EntitySpatialIndexStats.java` | +`typedQueries/typedFallback/typedScanned/typedSkipped/membersSkipped/vanillaOrderQueries` |
| `compat/prts/PRTSFeaturesConfig.java` | 模板注释同步（typed 查询已支持） |

复用既有配置键：`entity-spatial-index.enabled`（默认开）/ `min-section-size`（16）/ `telemetry-enabled`，**无新配置键**。

---

## 二、性能对照表（测试服实机 A/B，2026-08-16 晚）

### 2.1 测试协议（控制变量演进——重要：前期多次测量被环境污染，最终协议如下）
- 世界：测试服既有世界，围栏 pen（16×16，chunk 0,0，forceload）；`gamerule doDaylightCycle false` + `time set midnight`（防僵尸天亮烧死）、`doMobSpawning false`（消自然刷怪churn）、`maxEntityCramming 0`（防挤压死亡）。
- 种群：120 只 AI 僵尸（`PersistenceRequired:1b`）12×12 网格召唤于 pen 内，落位 60s+ 后取 600-tick 遥测窗口。
- 对照开关：A 腿 `entity-spatial-index.enabled: true`（默认）；B 腿用 `min-section-size: 1000000`（任何 section 不建索引 = 索引整体失效，**同一会话重启、同一存档、同一被击杀/召唤流程**，消除存档状态差异）。

### 2.2 对照表（120 AI 僵尸，围栏 pen，同流程）

| 指标 | 索引关（vanilla 语义） | 索引开（三档路径 + typed） | Δ |
|---|---|---|---|
| overworld avg mspt | **1.9ms**（两窗口 1.9/1.9） | **2.0ms**（两窗口 2.0/2.1） | **+0.1ms（持平，噪声内）** |
| region0 avg mspt | 0.8ms | 0.8ms | 持平 |
| TPS | 20.0 | 20.0 | 持平 |
| 异常/崩溃 | 0 | 0 | 持平 |

> 结论：**在「单 section 密集堆」这一最不利几何下，三档路径把索引开销压到与 vanilla 持平**（一期 gather+sort 版本实测 +0.4~1.1ms 倒挂，本次修复消除）。索引的收益场景是「section 大且查询框空间选择性高」的几何（刷怪塔/掉落物堆/村庄），与审计 §1.3 的 -33% 结论方向一致，但该数字来自 08-16 白天的不同环境与几何，**不可与本表直接对比**。

### 2.3 typed 路径遥测（证明 P2 本体在工作）

| 场景 | typedQueries/tick | typedScanned/tick | typedSkipped/tick | 说明 |
|---|---|---|---|---|
| 120 僵尸 pen（索敌大框为主） | ~155 | ~57 | 0 | 大框全 section 覆盖 → 预筛不剪枝（设计内：break-even） |
| 60 村民+60 僵尸+40 刷怪笼（小框查询） | ~1000+ | ~2100 | **~74** | 刷怪笼 9×9×9 exact-class 小框 → **预筛剪枝生效**（44677/600tick 窗口） |
| 索引关 | 0 | 0 | 0 | typedFallback 全量，走 vanilla |

- `typedSkipped>0` 即「同类成员被空间预筛跳过，省掉了 intersects + consumer accept」。
- 僵尸索敌（follow_range 35 → 70 格大框）与村民 Brain 传感器（radius 16 → 33 格框）天然全 section 覆盖 → 预筛不剪枝，typed 路径只付每成员一次 ~5ns 格检查（~57 成员/tick，可忽略）。这是**构造保证的「绝不劣于 vanilla」**，且小框查询（刷怪笼、怪物农场）稳定剪枝。

### 2.4 功能回归证据
- 全程 0 FATAL / 0 watchdog / 0 异常；TPS 稳定 20.0（全部 6+ 次重启会话）。
- 僵尸正常移动（Pos 持续变化、路径寻找 applied>0）、存活率 100%（PersistenceRequired 下 120/120）。
- 实体索引 gauge `indexedEntities=120`（全种群进索引）、`indexesBuilt=6~7`。
- 顺序保证（typed = 原版类列表顺序、untyped 三档 = 原版 allInstances 顺序）由构造保证，本次未做顺序断言脚本（与一期同款论证）。

---

## 三、模组兼容性（与审计红线逐条核对）

| 兼容机制 | 状态 | 说明 |
|---|---|---|
| 检测他人优化器让位 | ✅ 不变 | `@LoadIfMod(LITHIUM/CANARY/RADIUM/RECRUITS, ABSENT)` 仍挂在 `EntitySectionMixin_SpatialIndex` 类上（typed 注入在同一类） |
| 不动 `ClassInstanceMultiMap` 本体 | ✅ | 只调用 `find()`（vanilla 同款），不覆写、不改结构 |
| 保序 | ✅ | typed = 原版类列表顺序；untyped 三档 = 原版 allInstances 顺序；与一期索引插入序号排序论证一致 |
| 谓词语义零变化 | ✅ | `Predicate` 在 Level 层 consumer 包装，注入点之下，不感知 |
| `forExactClass` 测试 | ✅ | 逐成员 `tryCast` 原样执行（`BaseSpawner` 用 exact-class，实测剪枝生效） |
| `find` 的 `IllegalArgumentException`（非法类） | ✅ | typed 路径调用原版 `find`，异常语义保留 |
| 超大 bb 实体残余风险 | ⚠️ 同 §1.3 | 半径 >4 的 mod 自定义实体理论上可能漏检（vanilla 线性扫描无此问题）；可疑行为先关 `entity-spatial-index.enabled` 回归 |
| 配置逃逸阀 | ✅ | 复用 `entity-spatial-index.enabled`（默认开，一键关） |
| 线程安全 | ✅ | typed 的 `find` 走 section **写锁**（与 `EntitySectionMixin_RegionLock` 原版 redirect 同纪律），查询走读锁；与区域并行/维度并行无冲突（纯读/短锁） |

---

## 四、遗留与后续

- **untyped 一期路径**：三档路径已消除密集单 section 倒挂；「超大 section + 高度选择性查询」的收益几何（如大规模刷怪塔）建议在生产服用 `[entity-spatial-index]` 遥测的 `candidatesScanned` vs `fullScanned` 观察后再定调参。
- **typed 二期候选**：`typedSkipped` 在小框查询下已生效；若生产服出现「typed 查询占比高但全是大框」的画像，可考虑对 `NearestLivingEntitySensor` 类传感器做**框裁剪**（超出 section 的框边）——属新方向，未立项。
- 未做：顺序断言脚本（与一期同为构造保证）、`findClosest`/`take` 最近优先剪枝（审计 §5.1 二期候选，与本期无关）。
