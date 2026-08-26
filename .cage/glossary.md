# 术语表

本仓对话与文档中高频出现的专有词，统一在此给出标准含义。**定义以源码为准**：若某词在代码 / 实际输出与本文冲突，以代码为准并回报偏差（红线 8），不乱猜。

## 1. 性能与观测

| 术语 | 含义 | 备注 |
|---|---|---|
| TPS | Ticks Per Second，全 20 为跑满。低于 20 表示主线程或 tick 流程被拖住 | 判断标准，非唯一标准（见 MSPT） |
| MSPT | Milliseconds Per Tick，每 tick 平均耗时。50ms ≈ 跑满 20 TPS | spark / JFR 视角下的主线程时间账 |
| spark | 采样性能分析器，产出二进制 `*.sparkprofile` + 元数据 `.json` | `.cage/scripts/spark_quick.py` 解析 |
| JFR | Java Flight Recorder，JDK 内置采样，用于 L3 运行时归因 | 与 spark 同属运行时证据 |
| 归因规则 | spark 采样先剔 idle（park / yield / wait）再算 `self = inclusive − Σ(child inclusive)` | 不剔 idle 归因会虚高 |
| `cross.read` | 区域 worker 读跨权块位置时累加的计数，首要观测指标 | 它涨 = worker 读了非本区状态，需查 |
| `servercore status` | `/servercore status` 一行式并行引擎状态（BEPolicy / Journal / drain / 路由学习等） | 取证与回归必看 |

## 2. 并行引擎与线程模型

| 术语 | 含义 | 备注 |
|---|---|---|
| 主线程 | Server thread，世界的权威态所在，所有玩家交互 / 容器 / 菜单 / 事件都在此 | 红线 3：权威态永远在主线程 |
| worker | 并行 tick 线程（RegionTick / DimensionTick / AsyncPathfinding 等），只在 tick 边界与主线程汇合 | 红线 2：禁碰主线程专用状态 |
| RegionTick | 区域级并行 tick | `region_parallel` |
| DimensionTick | 维度级并行 tick | `dimension_parallel` |
| AsyncPathfinding | 异步寻路 worker 池，寻路本身已在多线程上 | `async_pathfinding` |
| barrier | 并行 tick 的汇合闸：同 chunk 的 tick 责任唯一，worker 在边界排队汇合 | 不是「可以省掉的复杂度」，见 concurrency.md §6 |
| barrier-timeout-ms | barrier 超时，默认 120000ms：真卡死时 dump 后崩服，防永久冻结 | 体系待办：30~60s 后恢复 thread dump |
| watchdog | 服务器看门狗，barrier 期间不得误判卡死 | 感知 barrier |
| BEPolicy | 块实体（BlockEntity）调度策略三态：`allow`（允许上 worker）`force`（强制主线程）`unsafe`（触发违规后被标记，本会话永久主线程） | 冒烟断言 `unsafe=0`；`BlockEntityAffinity` |
| EntityPolicy | 实体调度策略（同 BEPolicy 思路，`EntityAffinity`） | force / allow / seed / auto-ledger |
| ThreadPolicy | 线程路由策略开关：`stats`（只统计不拦截，默认）/ `enforce`（拦截，仅测试服） | 不预设一律 false，见红线 10 |
| WorldWriteJournal | 跨区写的入账队列：写入只入队、下一 tick 应用，带上限（默认 4096）与丢弃计数 | 读侧占位延迟 1 tick |
| Journal 丢弃 | `droppedOverflow`（超上限）/ `droppedUnloaded`（目标区块卸载）/ `failed`（应用失败）三档计数 | 任一 > 0 即异常，冒烟必须全 0 |
| drain | 主线程把待 tick 实体按类批量消费的过程；`RoutedDrainStats` 记账：每类实体耗时 + 路由原因 | 「drain 账本」= 谁主导主线程时间 |
| 违规计数 | 允许上 worker 的 BE / Entity 被 worker 触碰主线程专用状态而拦截的次数 | `unsafe=0` 是回归断言 |
| 路由学习 | 运行时按 Phase 2 违规窗口学习某类实体该留主线程还是上 worker | 种子前缀 + 学习器，auto 模式生效 |

## 3. 事件桥

| 术语 | 含义 | 备注 |
|---|---|---|
| EventBus / event bridge | 模组事件派发总线；事件数量与时机是 mod 契约 | 红线 1：禁止削减 / 延迟 / 合并 / 吞事件 |
| `BlockEvent` 系 | 方块事件族（等 `BlockDropItemEvent`），捕获链消费端，桥门集合必须含 | 缺则丢掉落事件 |
| 短路 / 预检 | 无线索时跳过派发；失败必须保守回退「有监听器」 | 红线 5：禁止误短路；返回值参与逻辑的事件不短路 |
| 按需注册 | `register(实例)` + `unregister(实例)`，不用无句柄的 `addListener` | 仅限主线程 |
| eager-registration | 逃生门配置：桥注册时机从 mod 加载迁到首个插件 enable 的已知差异 | 需要时开启 |
| MC-BOOTSTRAP | NeoForge 启动模块层，不可注入 mixin，只能反射查询 | 不能写该层的 mixin |

## 4. 兼容与构建

| 术语 | 含义 | 备注 |
|---|---|---|
| `@LoadIfMod(ABSENT)` | 检测到 Lithium / Canary / Radium 等同类优化器时让位不加载 | 红线 6 / 架构 7 |
| `alternate_current` | 红石替代实现 mod，遇则整体回退串行 | 红石类改动保守 |
| `@Invoker` | Mixin 的 invoker，必须用 abstract class | 架构 6 |
| MANIFEST / Implementation-Version | jar 清单里的版本号；dirty 指纹 = 有未提交改动 | 不允许带 dirty 交付 |
| `mod_file/*` / `gson.jar` / `class_cache` | arclight-common 改动后的缓存位置，必须清否则 mixin 静默失效 | 红线 11 |
| `-Dmixin.debug.export=true` | 导出确认 mixin 实际注入生效 | 验收前必跑 |
| 门控探针 | 临时埋点用于验证，验证后必删 | 提交预告点 |

## 5. 流程与证据

| 术语 | 含义 | 备注 |
|---|---|---|
| 冒烟测试 | `.cage/scripts/smoke_test.py`，产出 `smoke-report.log`，返回 0/1 | 每项必过，FAIL 不进下一步 |
| L1–L4 证据链 | 日志 / 静态字节码 / 运行采样 / 受控复现 | workflow §5 |
| P0 / P1 / P2 | 结论优先级：先零风险杠杆再小步并行 | capture §4 输出按此排序 |
| `[ASYNC-VS-THREADS]` | 评审第 4 条标签：异步 / 多线程混用裁决 | concurrency.md 触发词 |
| A/B 方案文档 | 同口径对比（交叉、预热、取中位、≥6 次） | workflow §10 / §11 |