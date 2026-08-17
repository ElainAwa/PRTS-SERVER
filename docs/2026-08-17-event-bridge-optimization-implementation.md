# 模组事件注册与使用优化：事件桥按需注册 + 空监听器预检 + 高频事件短路 —— 实现 + 性能对照表（2026-08-17，测试服实机）

> **文档性质**：AI 创作。对应计划稿 `2026-08-17-event-bridge-optimization-plan.md` 的落地记录（A-00~A-10 主体 + A-17/A-18 验证）。
> **审查基线**：分支 `feature/lightopti`，HEAD `56138d45` 之上新增本功能。
> **验证环境**：`prts-test` 测试服（NeoForge 1.21.1 + PRTS v1.0.36-Multithreading 构建 + spark 1.10.124 + 合成负载 mod `prtsloadgen`），RCON 控制。
> **兼容红线**：事件数量与时机是 mod 契约，绝不削减/延迟/合并；优化只降单个事件的内部成本；每个优化都有配置逃逸阀。

---

## 一、实现摘要（与计划稿的偏差及理由）

### 1.1 P0-1 桥监听器按需注册（A-02/A-03）

- **`EventBridgeRegistry`（新，arclight-common）**：门注册表 + 门状态机。平台层注册「门集合」（Bukkit 事件类 → 静态 HandlerList 列表），`HandlerList` 任何注册/注销变化后重评估，0→1 通知平台注册桥、1→0 通知注销。
- **`HandlerListMixin_EventBridge`（新，common，`mixins.arclight.impl.optimization.json`）**：在 `HandlerList` 的全部 8 个变更路径注入（register / registerAll / unregister×3 / unregisterAll×3 的 RETURN）——**比计划稿 A-02 的 `SimplePluginManager` 挂点更低层**：插件直接调 `HandlerList.unregister*` 的路径也被覆盖，门状态不可能因漏挂而失配。
- **`ArclightEventDispatcherRegistry`（重构，neoforge）**：`registerAllEventDispatchers()` → `init()`。5 个桥 dispatcher 按门注册/注销（`NeoForge.EVENT_BUS.register/unregister(Object)`，6.2.33/8.0.5 均支持注销）；`PRTSCommandDispatcher` 常驻。门集合：`BlockBreakEvent∪BlockDropItemEvent` / `BlockPlaceEvent` / `EntityTameEvent` / `PlayerTeleportEvent∪EntityTeleportEvent` / `ItemDespawnEvent`。
- **开关语义**：`on-demand-registration.enabled: false` 或 `eager-registration: true` → `setActive(false)` → 全注册恢复旧行为；默认开 → 按门收敛。

### 1.2 P0-2 空监听器 O(1) 预检（A-04）

- 5 个 dispatcher 方法头加 `XxxEvent.getHandlerList().getRegisteredListeners().length == 0` 预检（volatile 数组读，O(1)）。
- **BlockBreakEventDispatcher 捕获链特例**（计划 §2.4/§五）：`BlockDropItemEvent` 有监听时保留 `captureBlockBreakPlayer` 压栈（掉落捕获链消费端），只跳过空 `callEvent` 与回写；两者都空时整段跳过。
- `BlockMultiPlaceEvent` 继承 `BlockPlaceEvent` 共享 HandlerList，两个方法一个预检即可。

### 1.3 P1-3 / P1-4 / A-09 事件短路（A-06/A-07/A-08/A-10）

- **`EventBusQuery.hasListeners(Class)`（公共底座，A-06）**：读 `EventBus.getListenerList(Class)`（private，反射 + 静态缓存 Method）+ `ListenerList.getListeners()`（AtomicReference，O(1) 无锁）。
- **`ServerLevelMixin_EntityTickShortcircuit`（P1-3）**：@Redirect `ServerLevel.tickNonPassenger` 内 `EventHooks.fireEntityTickPre/Post`，无监听器时返回缓存默认 `Pre` 实例（entity=null，无人读取）并跳过 Post。
- **`LevelMixin_NeighborNotifyShortcircuit` + `ServerLevelMixin_NeighborNotifyShortcircuit`（P1-4）**：@Redirect `Level.updateNeighborsAt` 与 `ServerLevel.updateNeighborsAt` 内 `EventHooks.onNeighborNotify`，无监听器时返回缓存默认实例（isCanceled=false，原代码 pop 丢弃结果 / 早退分支读 false 与原行为一致）。
- **A-09**：`ArclightMod` 的 `setLevelTickCallbacks` 包装加 `LevelTickEvent.Pre/Post` 无监听器短路（无需 mixin）。
- 配置段 `event-shortcircuit:`（`entity-tick-event.enabled` 默认开 / `neighbor-notify-event.enabled` 默认开 / `telemetry-enabled`）+ `[event-shortcircuit]` 遥测。

### 1.4 与计划稿的偏差（均已在实施中核实）

| 计划稿假设（基于 eventbus 6.2.33） | 实况（bus 8.0.5 字节码核实） | 落地 |
|---|---|---|
| `Event.getListenerList()` 存在（转换器注入实例方法） | **不存在**——8.0.5 的 `Event` 只有 `isCanceled` 字段 | 改查 `EventBus.getListenerList(Class)`（private） |
| busID 索引 `ListenerList.getListeners(busID)` | **无 busID 机制**；`ListenerList.getListeners()` 无参（AtomicReference） | 按 8.0.5 实现 |
| mixin `EventBus` 加 @Invoker accessor | **`net.neoforged.bus.EventBus` 在 MC-BOOTSTRAP 模块层，mixin 无法注入**（实机 ClassCastException 验证） | 改**反射 + 静态缓存 Method**（热路径 ~30ns/查询，实测无感知） |
| `onNeighborNotify` 只在 `Level.updateNeighborsAt` 一处 | **`ServerLevel.updateNeighborsAt` 覆写也发**（字节码核实） | 两个 mixin 各 @Redirect 一处 |
| `tickNonPassenger` 是 fireEntityTickPre/Post 唯一调用点 | ✓ 核实 | @Redirect 挂调用方（项目既有模式） |

---

## 二、性能对照表（测试服实机 Spark A/B，2026-08-17）

### 2.1 测试协议

- 环境：prts-test（NeoForge 1.21.1 + PRTS，无真人玩家），RCON 控制。
- 合成负载：`prtsloadgen` 测试 mod 每 tick post N 个 `BlockEvent.BreakEvent`（模拟 QuarryPlus 整列破块事件流，N=2000/5000 两档）。
- 对照组：
  - **改动前**：`0eb91896` 构建（桥启动即全注册 + 每事件全量桥接）；
  - **B 腿（优化关）**：新 jar + `event-bridge.on-demand-registration.enabled: false` + `eager-registration: true` + `event-shortcircuit.*.enabled: false`（桥常驻 + P0-2 预检兜底，同配置重启消除构建差异）；
  - **A 腿（优化全开）**：新 jar 默认配置。
- 采样：`spark profiler start --timeout 120`（2400 ticks），`spark_quick.py` 归因（剔 idle）。
- 有插件腿：TestListener 测试插件监听全部门事件（NORMAL 优先级、不取消），验证转发一致性。

### 2.2 对照表（同负载同插件状态，全部 TPS 20.0）

| 场景 | MSPT mean | EventBus.post self% | 桥相关 self%（CraftMagicNumbers 等） | 遥测证据 |
|---|---|---|---|---|
| **5000 事件/tick 无插件** | | | | |
| 改动前（桥全量桥接） | **3.52ms** | 2.32% | 有（getMaterial 等入 top30） | — |
| A 腿（优化全开） | **2.21ms（-37%）** | **0.61%** | 0（桥不在总线） | `dispatcherRegister=0` |
| **2000 事件/tick 无插件** | | | | |
| 改动前 | 2.31ms | 2.05% | 3.40% | — |
| B 腿（桥常驻+预检兜底） | 1.50ms | 1.26% | 0 | `skippedEvents=1,200,000`（600 tick × 2000） |
| A 腿（优化全开） | 1.88ms | 0.77% | 0 | `dispatcherRegister=0` |
| **2000 事件/tick + 插件监听 + 120 僵尸** | | | | |
| A 腿（桥注册+转发） | 4.13ms | 1.13% | 转发正常 | `forwardedEvents=8,400,000`（4200 tick × 2000） |
| **5000 事件/tick + 插件监听** | | | | |
| A 腿（桥注册+转发） | 5.49ms | 2.30% | 10.01%（插件消费是契约） | `forwardedEvents` 持续增长 |

> **核心结论**：无插件（生产服 QuarryPlus/RTSbuilding 场景）下，同负载 5000 事件/tick 的 MSPT 从 **3.52ms 降至 2.21ms（-37%）**，`EventBus.post` 子树从 2.32% 降至 0.61%；2000 档桥相关 self 从 3.40% 归零。**有插件时转发量与改动前逐位一致**（事件一个不少），桥开销是插件契约的组成部分，不做削减。

### 2.3 遥测证据（功能回归）

| 断言 | 结果 |
|---|---|
| 无插件时桥不在总线 | `[event-bridge] dispatcherRegister=0`（A 腿 600-tick 窗口）✓ |
| `enabled:false`（B 腿）恢复常驻 | `dispatcherRegister=5` + `skippedEvents=120 万`（P0-2 预检兜底）✓ |
| 插件 enable → 0→1 注册 / disable → 1→0 注销 | `/reload` 风暴：`dispatcherRegister=15 dispatcherUnregister=10`（3 轮 reload，在场 5 个精确配对）✓ |
| 插件收到的 BlockBreakEvent 与 loadgen 对拍 | TestListener `tlcount`：2,366,000 → 2,526,000 → 持续增长，与 loadgen 2000/tick 逐位吻合 ✓ |
| EntityTickEvent 短路 | `[event-shortcircuit] skippedPre=290,567 skippedPost=290,567`（≈484/tick × 2，120 僵尸 + 世界实体）✓ |
| 短路在有插件时自动让位 | 插件不监听 Forge tick 事件 → 短路照常（`forwardedPre=0` 正确）✓ |
| 全程异常 | 0 FATAL / 0 watchdog / 0 异常（全部 8+ 会话）✓ |

---

## 三、模组兼容性（与计划 §4.4 逐条核对）

| 兼容机制 | 状态 | 说明 |
|---|---|---|
| 事件数量/时机零变化 | ✅ | Forge 事件照发、时序不变；变化只是 Arclight 自己的监听器是否在总线（无插件时转发本就是无人消费的空派发） |
| mod 监听器行为不变 | ✅ | 总线派发数组与 mod 监听器无关；dispatcher 缺席时 `setCanceled` 回写（原为 no-op）不再发生，语义等价 |
| 有插件时的行为逐位一致 | ✅ | 插件注册 → 同一 dispatcher 代码、同一 NORMAL 优先级；捕获链、取消回写全部保留（tlcount 对拍验证） |
| 捕获链（`BlockDropItemEvent` 依赖） | ✅ | 门集合含 `BlockDropItemEvent`；P0-2 特例：只有掉落监听时保留压栈 |
| 监听器顺序差异 | ⚠️ 已知 | 桥从「mod 加载期注册」移后到「首个插件 enable」；`eager-registration: true` 逃生门（A/B 已验证一致） |
| 线程安全 | ✅ | 注册/注销主线程（Bukkit 契约）；dispatcher 预检/短路在 worker 线程纯读（volatile 数组/AtomicReference + 静态 Method），无锁 |
| 让位机制 | ✅ | `arclight$hasListeners` 按 `ListenerList.getListeners().length` 判断，任何监听者存在即让位；无 `@LoadIfMod` 需要（无同类优化器，已核对） |
| 配置逃逸阀 | ✅ | `event-bridge.on-demand-registration.enabled`（默认开）/ `eager-registration`（默认关）/ `event-shortcircuit.*.enabled`（默认开）+ 遥测开关 |
| **MC-BOOTSTRAP 模块层限制** | ✅ 已规避 | bus jar 在 boot 模块层，mixin 不可注入（实机 ClassCastException 验证）→ 反射查询 + 失败保守回退（返回「有监听器」，绝不误短路） |

---

## 四、遗留与后续

- **P1-2 直接调用点 sweep（A-11/A-12）未做**：`callBlockFormEvent` 一族（流体成块/刷石机场景）与交互/死亡族——计划第二阶段，需逐点审计返回值语义；本期 A-00 基线（5000/tick 事件流）中该族占比未显著，留待生产服 spark 归因后再立项。
- **P2 遥测（A-13/A-14）未做**：`forceRebuild`/`buildCache` 重建归因与「注册-触发」匹配遥测——计划可选阶段。
- **LevelTickEvent 短路（A-09）**：未单独 A/B（收益量级已论证 ~0.6µs/tick，并入 tick 事件族开关）。
- **`event-shortcircuit` 的 LevelTickEvent 短路复用 `entity-tick-event.enabled` 开关**：tick 事件家族统一开关，文档登记。
- **生产服观察项**：`[event-bridge] forwardedEvents vs skippedEvents` 比例（确认桥在无插件时归零、有插件时正常）；`dispatcherRegister/Unregister` 配对（`/reload` 后应为 0 差值）；超大负载下 `EventBus.post` 子树占比回落情况。

---

## 五、改动文件清单

| 文件 | 改动 |
|---|---|
| `arclight-common/.../optimization/general/eventbridge/EventBridgeRegistry.java` | 新增：门注册表 + 门状态机 + PlatformBridge 回调 |
| `arclight-common/.../optimization/general/eventbridge/EventBridgeStats.java` | 新增：`[event-bridge]` 遥测 |
| `arclight-common/.../optimization/general/eventbridge/EventShortcircuitStats.java` | 新增：`[event-shortcircuit]` 遥测 |
| `arclight-common/.../mixin/optimization/general/eventbridge/HandlerListMixin_EventBridge.java` | 新增：HandlerList 全路径变更通知 |
| `arclight-common/.../compat/prts/PRTSFeaturesConfig.java` | `event-bridge:` / `event-shortcircuit:` 配置段 + 模板注释 |
| `arclight-common/.../compat/prts/PRTSFeatures.java` | 两个 Stats tick 接线 |
| `arclight-common/src/main/resources/mixins.arclight.impl.optimization.json` | +`eventbridge.HandlerListMixin_EventBridge` |
| `arclight-neoforge/.../mod/event/ArclightEventDispatcherRegistry.java` | 重构：按需注册/注销 + 门集合 + `PRTSCommandDispatcher` 常驻 |
| `arclight-neoforge/.../mod/event/BlockBreakEventDispatcher.java` | P0-2 预检 + 捕获链特例 + 转发计数 |
| `arclight-neoforge/.../mod/event/BlockPlaceEventDispatcher.java` | P0-2 预检 + 转发计数 |
| `arclight-neoforge/.../mod/event/EntityEventDispatcher.java` | P0-2 预检 + 转发计数 |
| `arclight-neoforge/.../mod/event/EntityTeleportEventDispatcher.java` | P0-2 预检（按分支）+ 转发计数 |
| `arclight-neoforge/.../mod/event/ItemEntityEventDispatcher.java` | P0-2 预检 + 转发计数 |
| `arclight-neoforge/.../mod/event/EventBusQuery.java` | 新增：反射监听查询（A-06 底座） |
| `arclight-neoforge/.../mixin/eventbridge/ServerLevelMixin_EntityTickShortcircuit.java` | 新增：P1-3 EntityTickEvent 短路 |
| `arclight-neoforge/.../mixin/eventbridge/LevelMixin_NeighborNotifyShortcircuit.java` | 新增：P1-4 NeighborNotifyEvent 短路（基类） |
| `arclight-neoforge/.../mixin/eventbridge/ServerLevelMixin_NeighborNotifyShortcircuit.java` | 新增：P1-4（ServerLevel 覆写） |
| `arclight-neoforge/.../ArclightMod.java` | `init()` 接线 + A-09 LevelTickEvent 短路包装 |
| `arclight-neoforge/src/main/resources/mixins.arclight.neoforge.json` | +3 个 eventbridge mixin |
