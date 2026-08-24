# 架构不变量约束

## 1. 线程模型

- 世界权威态永远在主线程；worker 仅在 tick 边界与主线程汇合。
- 有玩家维度必须主线程串行；玩家保持主线程（容器 / 菜单 / 事件）。
- 并行会话按「方块 → 实体 → BE」串行；同一 chunk 的 tick 责任唯一。
- 实体生成只能由主线程驱动（`applyStep` 收归主线程），`addEntity` 路径强制回主线程，宁可损失并行度也要保证 UUID 唯一。
- 区块生成提交有预算（`generation-tasks-per-tick`）与滚动窗口限流（`chunkgen-inflight-limit`）。

## 2. worker 禁碰清单（ThreadPolicy 边界）

- `getBlockEntity` / `getChunkNow` 等主线程 API。
- Colony / POI、实体索引、邻居更新器、AC 红石图（红石类改动保守、默认值经评估决定，运行时检测到违规即整体回退串行）。
- 读侧只信 `visibleChunkMap` + 身份复核；跨线程 fastutil 引用须 volatile 换引用。

## 3. 跨区写（WorldWriteJournal）

- 只入队、下一 tick 应用、带上限（默认 4096）与丢弃计数；读侧占位延迟 1 tick。
- `Journal dropped*` 大于 0 即视为异常，冒烟测试必须为 0。

## 4. barrier / watchdog

- barrier 期间 watchdog 不得误判卡死（watchdog 感知）。
- `barrier-timeout-ms` 默认 120000：真卡死时 dump 后崩服；parallel tick 期间 watchdog 的静默抑制必须有硬上限（待办：30~60s 后恢复 thread dump / 击杀，防止真死锁时永久冻结）。

## 5. 事件桥（event bridge）

- 事件数量与时机是 mod 契约：禁止削减、延迟、合并、吞事件；只降单个事件的内部成本（构造、派发查找、桥分配、锁）。
- 不改 `EventBus.post` 派发本体；不做事件池；不降频合并；不跳过「黑盒不可判定」的监听器。
- 按需注册必须 `register(实例)` + `unregister(实例)`，不得用无句柄的 `addListener`。
- 注册 / 注销仅限主线程；worker 上的预检 / 短路必须纯读无锁；`LevelTick` Pre / Post 保持主线程 fire。
- 短路 / 预检失败时保守回退「有监听器」，绝不误短路；返回值参与逻辑的事件（伤害、刷怪判定）不短路。
- `BlockBreak` 桥门集合必须含 `BlockDropItemEvent`（捕获链消费端），缺则丢掉落事件。
- 桥注册时机从 mod 加载期移至首个插件 enable 是已知差异，须留 `eager-registration` 逃生门。
- MC-BOOTSTRAP 模块层不可注入 mixin，改用反射查询。

## 6. 锁纪律

- 锁序固定 L1 → L2，L1 从不嵌套；禁止改 final 字段。
- `@Invoker` 必须 abstract class。
- find 走写锁、查询走读锁；主线程独占路径不加锁。
- 寻路超时处理必须清 pending。

## 7. 兼容纪律

- 检测到 Lithium / Canary / Radium 等同类优化器：`@LoadIfMod(ABSENT)` 让位；未知 mod 默认回退 vanilla。
- 红石类改动保守为先（默认值经评估决定），遇 `alternate_current` 整体回退串行；活塞批量原子化不做；区块保存完整性语义不能变。
- 只改服务器核心，不动第三方 mod 源码；兼容逻辑收敛 `compat/` 命名空间（如 `compat/tacz`）；反射无编译依赖 + try/catch 空转 + instanceof 兜底 + 幂等。
- 配置新增带模板注释，默认值保守。

## 8. 正确性优先原则（过载与并行调优）

- 过载场景先做正确性（如 UUID 实体重复 P0）与零风险杠杆（sim-distance、寻路 / 繁殖预算、清怪），再谈扩大并行。
- BE 并行小步开：先局部状态的类型（Funnel / Chute / Belt），后交互多的（MechanicalCrafter / Display）；每加 1~2 个类型盯一次 violation 计数。
- `thread-policy` 保持 `stats`（只统计不拦截）；过载状态下不建议开 enforcement。
- 每一步用 ThreadPolicy 违规率与 TPS 验证，不要一次性多开。
