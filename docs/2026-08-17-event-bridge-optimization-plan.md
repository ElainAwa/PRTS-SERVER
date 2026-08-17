# 模组事件注册与使用优化：实测归因修正 + 事件桥按需注册方案（2026-08-17 计划稿）

> **文档性质**：AI 创作。对应审计文档 `2026-08-16-vanilla-server-highload-hotspots-audit.md` §〇·一 / §五①（P0「削模组事件洪流的内部成本」）的落地计划。
> **审查基线**：分支 `feature/lightopti`；审阅对象：NeoForge 21.1.248 + eventbus 6.2.33（`net.minecraftforge:eventbus`，NeoForge 1.21.1 实际装载版本）+ `arclight-common` / `arclight-neoforge` 事件桥。
> **本文档只做分析与方案设计，不写代码**（与审计文档同款定位）；所有「现状」结论均对照 6.2.33 字节码 / 21.1.248 源码 / 本仓库代码核实。
> **兼容红线（与审计 §四 完全一致）**：事件**数量与时机是 mod 契约，绝不削减/延迟/合并**；优化只降**单个事件的内部成本**（构造、派发查找、桥接分配、锁），并且每个优化都有配置逃逸阀。

---

## 〇、TL;DR

1. **审计 §五① 的三条假设需要修正**（已对照 6.2.33 字节码核实）：
   - 「HashMap 查找」→ 只在**未转换的事件类**（插件运行时定义的类等）与**注册期**存在；热路径上事件类的 `ListenerList` 是**转换器注入的静态字段**（`GETSTATIC` 直读）。
   - 「锁竞争」→ 派发热路径**零锁**（volatile 缓存数组直读）；锁只在注册/注销（Semaphore + forceRebuild 级联）。
   - 「多态派发」→ 真实存在但每事件仅 1 次虚拟调用 + 每监听器 1 次接口调用，不是 75.5% 的主体。
2. **75.5% 的真实构成**（修正后）：① 事件对象构造（`BlockEvent.BreakEvent` 等，含调用侧 pre-cancel 预读）；② **Arclight 事件桥的每事件分配与空派发**（无插件监听时也全量构造 `CraftBlock` + Bukkit 事件 + 走一次空循环派发）——**这是 PRTS 可以直接消除的部分**；③ 监听器主体（QuarryPlus / RTSbuilding 自身逻辑，mod 代码，PRTS 不可动）；④ 下游锁（mod 监听器内部同步、worker 跨线程派发）。
3. **主方案（P0-1）**：Arclight 的 6 个 Forge 桥 dispatcher 从「启动时无条件注册」改为**按「有插件在听对应 Bukkit 事件」按需注册/注销**（`SimplePluginManager` 注册/注销路径挂钩，0→1 注册、1→0 注销）。无插件监听的服务器上，Forge 事件照发、mod 监听器照收，**只是 Arclight 自己的监听器不在总线上**——桥开销整块归零。
4. **防御层（P0-2）**：dispatcher 入口 O(1) 预检对应 Bukkit `HandlerList` 是否为空（volatile 缓存数组 `length == 0`），空则跳过事件构造。独立可用，也可作 P0-1 的兜底与注册竞态窗口保护。
5. **P1-2**：对**不走 Forge 总线**的直接调用点（`callBlockFormEvent` / 死亡 / 掉落等，分散在 arclight-common mixin 里）做同类空监听器预检 sweep——逐个调用点审计返回值语义后加预检，纯「无监听器时构造为零」。
6. **扫查补充（§8.5）**：vanilla patched 代码全量扫查后新发现两个「无监听器短路」立项项——**P1-3 `EntityTickEvent`**（每实体每 tick ×2，频率之王，1000 实体 ≈ 0.2–0.6% tick 预算）与 **P1-4 `NeighborNotifyEvent`**（NeoForge 在 vanilla 空壳 `updateNeighborsAt` 上 fire 事件且**丢弃结果**——零语义风险），共享底座 `arclight$hasListeners(Class)`。
7. **明确不做**：改 EventBus 派发本体、事件池、事件降频/合并、吞事件（审计红线）。
8. 顺序：**P0-1 → P0-2 → P1-3/P1-4 →（spark 复测）→ P1-2 sweep → P2-3（可选）**，全部走「配置默认开（或保守）/ 遥测可观测 / 实机 A/B / 回归」闭环。

---

## 一、前置事实修正：审计 §五① 的假设 vs 6.2.33 实况（字节码核实）

### 1.1 `EventBus.post` 热路径（逐指令核实，eventbus 6.2.33）

```
post(Event e):
  1. invokedynamic #12 → (listener, event) -> listener.invoke(event)   // 常量 lambda，一次解析后零成本
  2. shutdown volatile 读（恒 false）
  3. checkTypesOnDispatch 读（系统属性 eventbus.checkTypesOnDispatch，默认 false → 跳过）
  4. e.getListenerList() —— 虚拟调用
     · 转换过的事件类（EventSubclassTransformer 在类加载时注入
       private static final ListenerList listenerList + getListenerList() 覆写，方法体 = GETSTATIC; ARETURN）
       → 一次静态字段读，无查找
     · 未转换的事件类（插件运行时定义的匿名/动态事件，罕见）
       → EventListenerHelper.getListenerListInternal → Cache.get（默认 CacheConcurrent，CHM 无锁读）
         + 罕见 miss 时 computeIfAbsent（反射实例化，且 computeListenerList 在锁外执行）
  5. listenerList.getListeners(busID) → lists[busID] 数组取 → ListenerListInst.getListeners()
     · volatile IEventListener[] 直读；非 null 直接返回（命中即零锁）
     · null（注册/注销刚 invalidate）→ buildCache：按优先级合并父链 + toArray（冷）
  6. 遍历数组：每监听器
     · if (!trackPhases && listeners[i].getClass() == EventPriority.class) continue;   // 主总线 trackPhases=false，
       // 但 buildCache 只对 tracking bus（phasesToTrack=ALL_PHASES）插入相位标记 → 该检查恒不命中，每监听器 1 次类比较
     · dispatcher.invoke(listener, e) → listener.invoke(e) 接口调用 → ASM 生成处理器直调目标方法
  7. return e.isCanceled()   // 字段读
```

**结论**：单事件派发开销 ≈ 2 次虚拟调用 + 1 次数组取 + O(监听器数)×(1 类比较 + 1 接口调用 + 主体)。**每事件约 10–30ns 级，不是 75.5% 的主体。**

### 1.2 注册 / 注销路径（冷路径，但动态 mod 会走到）

```
EventBus.register/unregister(Object):
  · registerClass/registerObject：反射扫 @SubscribeEvent 方法（注册期一次）
  · 每监听器 → ASMEventHandler.create（ASM 生成处理类）
  · ListenerListInst.register：Semaphore.acquire → 优先级 ArrayList.add → release → forceRebuild()
  · forceRebuild：本实例 + children 级联置 listeners = null（children 是 synchronizedList）
  · 下一次 post 重建：buildCache 逐优先级调 getListeners(EventPriority)（该方法**每次都 acquire Semaphore**）+ 父链合并
```

**结论**：注册/注销触发的是**缓存失效 + 下个事件的重建**，且**级联到全部子类事件**。若某 mod 在运行期高频动态注册/注销（每 tick / 每方块实体），会造成持续重建（每次重建带锁）。这是「锁竞争」的一个真实来源——但来自**注册行为**，不是派发行为。

### 1.3 审计断言修正表

| 审计 §五① 断言（2026-08-16） | 6.2.33 实况（2026-08-17 核实） | 修正后定位 |
|---|---|---|
| 多态派发 | 存在：`getListenerList()` 虚拟调用 + `listener.invoke` 接口调用 | 真实但量小（每事件 ~2 次间接调用） |
| HashMap 查找（`ListenerList`/`EventBusData`） | 转换器注入静态字段，热路径 GETSTATIC 直读；`EventListenerHelper` 的 CHM 缓存只服务**未转换类**与父链解析 | 热路径基本不存在；仅在插件运行时自定义事件类上成立 |
| 锁竞争被放大 | 派发热路径**零锁**（volatile 数组直读）；锁只在注册/注销（Semaphore）、动态注册风暴的 forceRebuild 级联、以及**监听器主体内部**（mod 代码） | 锁竞争属下游：mod 监听器同步 + 动态注册风暴；PRTS worker 跨线程派发会放大 mod 内部的竞争 |
| 「缓存 post 目标」建议 | **6.2.33 已实现**（per-ListenerList 缓存数组 + 注册时失效），且带 `trackPhases`/异常/`isCanceled` 语义 | 无需再实现；本文档不再重复立项 |

> **对「未来演进 §14.3」的核对**：两份 HTML 文档 §14.3 均无事件总线相关立项（子代理全文检索确认），本方案不与既有演进冲突。

### 1.4 75.5% 的真实成本归属（修正后）

| 层 | 成本构成 | PRTS 可动性 |
|---|---|---|
| ① 事件构造 | `new BlockEvent.BreakEvent(level, pos, state, player)` 链式构造；调用侧 `CommonHooks.fireBlockBreak` 每块还付 `getMainHandItem` + `canAttackBlock` + `blockActionRestricted` + `GameMasterBlock` 预读 | 不可动（vanilla/NeoForge 调用侧） |
| ② **Arclight 事件桥** | 每 BreakEvent：`DistValidate.isValid` + **`CraftBlock.at` 分配** + **`BlockBreakEvent` 分配** + 捕获入栈 + `Bukkit.getPluginManager().callEvent`（线程检查 ×2 + 空循环） + `setCanceled(false)` 回写 | ✅ **可动（本方案 P0/P1 主体）** |
| ③ 监听器主体 | QuarryPlus `breakBlocks`（整列破块 + 自身逻辑）、RTSbuilding `copyTags`（事件内 NBT 深拷贝） | ❌ mod 代码，不可动 |
| ④ 下游锁 | mod 监听器内部同步；worker 线程跨线程派发放大；动态注册风暴 | ⚠️ 只能「检测 + 提示」（§九 P2-2），不能改语义 |

---

## 二、PRTS 侧事件路径盘点（本轮审计的完整清单）

### 2.1 Forge 桥 dispatcher（6 个，`ArclightMod` 构造时无条件注册）

`ArclightEventDispatcherRegistry.registerAllEventDispatchers()`（`arclight-neoforge/.../mod/event/`），注册时机 = mod 构造（早于插件 enable）：

| Dispatcher | 监听的 Forge 事件 | 产出 Bukkit 事件 | 热频 | 现状门控 |
|---|---|---|---|---|
| `BlockBreakEventDispatcher` | `BlockEvent.BreakEvent`（`receiveCanceled`） | `BlockBreakEvent`（+ 破块捕获链，见 §2.4） | **最高**（玩家挖掘 + QuarryPlus 类 mod 每块一发） | **无门控**——每事件全量桥接 |
| `BlockPlaceEventDispatcher` | `BlockEvent.EntityPlaceEvent` / `EntityMultiPlaceEvent` | `BlockPlaceEvent` / `BlockMultiPlaceEvent` | 中（玩家放置） | 门控于 `ArclightCaptures.getPlaceEventDirection() != null`（仅玩家路径） |
| `EntityEventDispatcher` | `AnimalTameEvent` | `EntityTameEvent` | 低 | 无 |
| `EntityTeleportEventDispatcher` | `EntityTeleportEvent.EnderEntity` | `PlayerTeleportEvent` / `EntityTeleportEvent` | 低（末影珍珠） | 无 |
| `ItemEntityEventDispatcher` | `ItemExpireEvent`（`ItemEntity.tick` 每物品到期一次，源码核实） | `ItemDespawnEvent` | 中（物品堆到期） | 无 |
| `PRTSCommandDispatcher` | 命令注册事件 | —（非事件桥） | — | **保留常驻** |

**核心问题**：`BlockBreakEventDispatcher` 无门控——生产服 QuarryPlus 整列破块（审计 31.6%→16.8%）的**每块** `BreakEvent` 都付「Forge 派发 → 桥构造（2+ 分配）→ Bukkit 空派发（0 监听器）→ 回写」。无插件监听的服务器上这是 100% 浪费。

### 2.2 直接调用点（不走 Forge 总线，`arclight-common` mixin 直发 Bukkit 事件）

| 调用点 | Bukkit 事件 | 热频 |
|---|---|---|
| `ArclightEventFactory.callBlockFormEvent`（Liquid/Lava/ConcretePowder/ServerLevel/SnowGolem/ReplaceBlock/ReplaceDisk/LivingEntity 等 ~8 处 mixin） | `BlockFormEvent` / `EntityBlockFormEvent` | **高**（流体成块每 tick；刷石机/刷冰机场景） |
| `EntityEventHandler.monitorLivingDrops` → `callPlayerDeathEvent` / `callEntityDeathEvent` | `PlayerDeathEvent` / `EntityDeathEvent` | 中（每次死亡） |
| `CraftEventFactoryMixin`（损坏、交互、拾取等，vendored 源由 mixin 改写） | 各对应事件 | 中高 |
| `CraftEventFactory.callItemDespawnEvent`（经 `ItemEntityEventDispatcher` 调用） | `ItemDespawnEvent` | 中 |
| `ServerPlayerGameModeMixin_NeoForge` / `ArclightEventFactory.onBlockBreak`（vanilla 平台路径，neoforge 平台由桥承担） | `BlockBreakEvent` 等 | 最高（neoforge 已并入 §2.1 桥） |

这些调用点的通病与 §2.1 相同：**无插件监听时也构造事件 + 空派发**，且不在 `EventBus.post` 子树里（spark 归因不到），属于同一类「可安全消除的浪费」。

### 2.3 Bukkit 派发侧（vendored Spigot-API，源码核实）

- `SimplePluginManager.callEvent`：异步事件线程检查 + `server.isPrimaryThread()`（被 `SimplePluginManagerMixin_DimParallel` @Redirect 放行 PRTS 维度/区域 worker）→ `fireEvent`。
- `fireEvent`：`event.getHandlers().getRegisteredListeners()` —— `HandlerList` 是 **volatile `RegisteredListener[]` 缓存**（register/unregister 置 null，`bake()` 重建，`getRegisteredListeners` 恒 O(1)）→ 空数组时循环零次。
- **推论**：`HandlerList.getRegisteredListeners().length == 0` 是一个 O(1)（1 次 volatile 读 + 1 次数组读）的「无监听器」判定——P0-2 预检的基石。

### 2.4 破块捕获链（`ArclightCaptures` / 与 P0-1 的安全边界）

- `BlockBreakEventDispatcher` 里 `captureBlockBreakPlayer(breakEvent)` 把 `BlockBreakEventContext`（含**掉落捕获列表**、`isDropItems`、方块 state）压入 `blockBreakEventStack`。
- 消费方（**与插件监听器无关，是 Arclight 的掉落机制本身**）：`BlockMixin.playerDestroy` RETURN / `ServerPlayerGameModeMixin_NeoForge` 的 `arclight$handleSecondaryBlockBreakEvents` → `bridge$handleBlockDrop` → `CraftEventFactory.handleBlockDropItemEvent`（发 `BlockDropItemEvent` 并处理掉落列表）。
- **含义**：`BlockBreakEventDispatcher` 的「按需注册」门集合**必须包含 `BlockDropItemEvent`**——只要有任何插件在听 `BlockDropItemEvent`，捕获链就必须活着（否则该插件的掉落事件丢失）。注册时机的其余安全论证见 §4.3。
- 静态证据：neoforge 平台上 `captureBlockBreakPlayer` 压栈的 context 无其它消费方；门集合覆盖 `{BlockBreakEvent, BlockDropItemEvent}` 后与现状逐位等价。

---

## 三、优化方案总览

| 优先级 | 方案 | 位置 | 收益 | 兼容风险 | 状态 |
|---|---|---|---|---|---|
| **P0-1** | 桥监听器**按需注册**（0→1 注册 / 1→0 注销） | `ArclightEventDispatcherRegistry` + `SimplePluginManager` mixin | 高（无插件监听时桥开销整块归零；生产服第一大热点的 PRTS 侧全部） | 低（事件照发；仅自己的监听器不在总线；门集合含捕获链依赖） | 本方案主体 |
| **P0-2** | dispatcher 入口 **O(1) 空监听器预检** | 6 个 dispatcher 方法头 | 中高（独立可用；覆盖 P0-1 注册窗口与「有插件但事件类无监听」场景） | 极低（预检 = 原派发结果的提前等价判定，构造为纯函数） | 防御层 |
| **P1-1** | 桥主体减配（保留事件语义的廉价化） | dispatcher 内部（构造/预读削减） | 中（有插件时） | 低 | 与 P0 同时实施，按实测取舍 |
| **P1-2** | **直接调用点**空监听器预检 sweep | `callBlockFormEvent` / 死亡 / 掉落等 ~10+ 调用点 | 中高（刷石机/流体成块场景） | 低（逐个审计返回值语义；无监听器 = 构造为零） | 第二阶段 |
| **P1-3** | `EntityTickEvent` 无监听器短路（每实体每 tick ×2，频率之王） | mixin `ServerLevel.tickNonPassenger` @Redirect | 高（1000 实体 ≈ 0.2–0.6% tick 预算；零语义风险） | 极低（无监听器 = Pre 恒未取消 = tick 照跑，逐位等价） | 立项（§8.5） |
| **P1-4** | `NeighborNotifyEvent` 无监听器短路（`updateNeighborsAt` 空壳 + 结果丢弃） | mixin `Level.updateNeighborsAt` @Redirect | 中（红石/活塞机械服） | **零**（原代码丢弃 `isCanceled` 结果） | 立项（§8.5） |
| **P2-1** | 事件总线遥测（派发/重建归因） | mixin `forceRebuild`/`buildCache` 计数 + 桥转发计数 | —（归因工具） | 无（只统计不改语义） | 与 P0 并行 |
| **P2-2** | 明确不做清单 + 让位 + 上游跟踪 | — | — | — | §九 |
| **P2-3** | `MobSpawnEvent` 系短路（可选） | mixin `NaturalSpawner` | 中（刷怪密集服） | 低（短路走原逻辑，保留默认结果） | 先 spark 归因（§8.5） |

---

## 四、P0-1：桥监听器按需注册（主方案）

### 4.1 机制

```
现状：ArclightMod 构造 → registerAllEventDispatchers() → 6 个 dispatcher 无条件常驻 NeoForge.EVENT_BUS

改后：
  ArclightMod 构造 → 只注册 PRTSCommandDispatcher（非桥，常驻）
  其余 5 个桥 dispatcher 由「桥注册表」（新类，替代 registerAllEventDispatchers 的静态语义）托管：
    · 维护 Map<ForgeDispatcher, Set<BukkitEventClass>> 映射（见 4.2）
    · 维护 AtomicInteger 计数：BukkitEventClass → 已注册插件监听器数
    · 挂钩 SimplePluginManager 的注册/注销路径（mixin，与 SimplePluginManagerMixin_DimParallel 同文件族）：
        registerEvent / registerEvents（经 createRegisteredListeners 逐事件注册）→ 对每个 Bukkit 事件类 count+1；0→1 时注册对应 Forge dispatcher
        unregisterEvent / unregisterEvents / unregisterAll / HandlerList.unregister* → count-1；1→0 时注销对应 Forge dispatcher（IEventBus.unregister，6.2.33 支持，已核实）
```

- **注册粒度**：按「Forge dispatcher」整体（不是按单事件），映射表 4.2 决定每个 dispatcher 的门集合。
- **注销语义**：`EventBus.unregister(Object)` → 逐监听器 `ListenerListInst.unregister`（Semaphore + 优先级列表 remove + forceRebuild 级联）——低频（插件启停），成本可接受，且与现状「插件启停时 Bukkit HandlerList 重建」同频。
- **P0-2 预检依然保留**（见 §五）：P0-1 的注册窗口 + 门集合内的部分事件类无监听时，由预检兜底。

### 4.2 映射表（门集合 = 该 dispatcher 存活所需的全部 Bukkit 事件类）

| Forge dispatcher | 门集合（任一有插件监听 → 注册） | 说明 |
|---|---|---|
| `BlockBreakEventDispatcher` | `BlockBreakEvent` **∪ `BlockDropItemEvent`** | `BlockDropItemEvent` 是捕获链的消费端（§2.4），缺它丢掉落事件 |
| `BlockPlaceEventDispatcher` | `BlockPlaceEvent` ∪ `BlockMultiPlaceEvent` | 两事件由同一 dispatcher 的两个方法产出 |
| `EntityEventDispatcher` | `EntityTameEvent` | `callEntityTameEvent` 构造该事件 |
| `EntityTeleportEventDispatcher` | `PlayerTeleportEvent` ∪ `EntityTeleportEvent` | `EnderEntity` 一个 Forge 事件分两条 Bukkit 分支 |
| `ItemEntityEventDispatcher` | `ItemDespawnEvent` | `callItemDespawnEvent` 构造该事件 |

### 4.3 线程与竞态

- **注册/注销发生线程**：插件 enable/disable 与 `/reload` 都在主线程，`SimplePluginManager` 的 register/unregister 调用点在主线程（Bukkit 契约：事件注册仅主线程）。计数用普通 `int` + 主线程断言即可（与 HandlerList 的 register 线程纪律一致）。
- **注册窗口竞态**：插件注册监听器（主线程）→ 计数 0→1 → `NeoForge.EVENT_BUS.register(dispatcher)`。窗口内（微秒级）：worker 线程上若恰有 Forge 事件派发且插件已就位但 dispatcher 未注册 → 该次桥事件可能错过。量化：仅插件 enable/disable 瞬间、单次事件、且窗口远小于 1 tick；mod 事件在 worker 上的派发点（QuarryPlus 机器 tick）与插件 enable（主线程）天然不同时。**缓解**：mixin 挂在 `HandlerList.register` **之前**的 `SimplePluginManager.registerEvent` 入口（先注册桥、后暴露插件监听器），把窗口压到不可达；`/reload` 期间若出现异常行为，`event-bridge.on-demand-registration` 可一键关回常驻（配置逃逸阀）。
- **与 `SimplePluginManagerMixin_DimParallel` 的关系**：无冲突——该 mixin 只管 `callEvent` 的线程放行，本方案只在注册/注销路径挂钩。
- **注销窗口**：插件 disable → 计数 1→0 → 注销 dispatcher。窗口内 dispatcher 仍活着、桥照发（多转发，不漏）——无害。

### 4.4 兼容红线逐条核对

| 兼容机制 | 状态 | 说明 |
|---|---|---|
| 事件数量/时机零变化 | ✅ | Forge 事件照发、时序不变；`CommonHooks.fireBlockBreak` / mod 自发事件原样；**变化的只是 Arclight 自己的监听器是否在总线上**——无插件监听时它的转发本就是无人消费的空派发 |
| mod 监听器行为不变 | ✅ | 总线派发数组与 mod 监听器完全无关（mod 监听器照收照跑）；dispatcher 缺席时 `event.setCanceled(false)` 回写（原为 no-op）不再发生，语义等价 |
| `receiveCanceled` 语义 | ✅ | 无插件时原行为 = 读 canceled 再写回原值（no-op）；缺席后该 no-op 消失，无观察差异 |
| 有插件时的行为逐位一致 | ✅ | 插件注册 → 同一 dispatcher 代码、同一注册顺序（NORMAL 优先级）；`captureBlockBreakPlayer` 捕获链、`handleBlockDropItemEvent`、取消回写全部保留 |
| 捕获链（`BlockDropItemEvent` 依赖） | ✅ | 门集合含 `BlockDropItemEvent`；实施时以「开启后掉落事件断言脚本」双保险（§十） |
| 监听器顺序细节 | ⚠️ 已知差异 | 桥从「mod 加载期注册」变为「首个插件 enable 时注册」，在 NORMAL 优先级数组中的相对位置移到所有 mod 之后。理论上影响「依赖桥取消回写的 mod 监听器」（罕见且无据可查）；配置 `event-bridge.eager-registration: true` 可恢复常驻旧行为 |
| 线程安全 | ✅ | 注册/注销主线程；计数主线程；与区域并行/维度并行无新增交互 |
| 配置逃逸阀 | ✅ | `event-bridge.on-demand-registration.enabled`（默认开）+ `event-bridge.eager-registration`（默认关，供顺序敏感场景）+ `telemetry-enabled`（默认开） |
| 无已知同类优化器冲突 | ✅ | 无同类 mod；如未来出现，沿用 `@LoadIfMod(ABSENT)` 让位机制（§九） |

### 4.5 配置与遥测

- 新配置段（照 `PRTSFeaturesConfig` 既有风格，进 `prts-features.yml` 模板注释）：

```yaml
event-bridge:
  on-demand-registration:
    enabled: true          # 桥监听器按需注册（无插件监听对应 Bukkit 事件时不注册/自动注销）
    eager-registration: false  # 恢复 mod 加载期常驻注册（顺序敏感场景的逃生门）
    telemetry-enabled: true    # 采集转发/跳过/注册注销计数进 [event-bridge] 日志
```

- `[event-bridge]` 遥测（新 `EventBridgeStats`，挂进 `PRTSFeatures.tick`）：`dispatcherRegister`/`dispatcherUnregister`、`forwardedEvents`（有插件时转发数）、`skippedEvents`（P0-2 预检跳过数）、`capturedOnly`（仅捕获链存活场景）。tick 汇总间隔与既有 Stats 一致。

---

## 五、P0-2：空监听器 O(1) 预检（防御层，独立可用）

- **做法**：每个桥 dispatcher 方法头第一行：

```java
if (BlockBreakEvent.getHandlerList().getRegisteredListeners().length == 0) {
    // 仍需维护捕获链：BlockDropItemEvent 在听时压 context（含掉落捕获），否则整体跳过
    ...
    return;
}
```

- **依据**：`HandlerList.getRegisteredListeners()` 是 volatile 缓存数组（§2.3），O(1)；无监听器时 `callEvent` 的派发结果恒为「未取消、无字段变更」——预检是**原派发结果的提前等价判定**（与 §5.6 菜单广播预检同款论证：零漏检、零多报）。
- **职责划分**：P0-1 负责「总线级缺席」（最大收益），P0-2 负责「dispatcher 在场但对应 Bukkit 事件无人听」的剩余场景：
  - P0-1 注册窗口（§4.3 缓解后仍存在的理论窗口）；
  - 门集合内部分事件类无监听（如只有 `BlockDropItemEvent` 监听时，`BlockBreakEvent` 构造可跳过、只压捕获栈）；
  - `eager-registration: true`（P0-1 关闭）时的兜底——单独开启 P0-2 也拿到大部分收益。
- **BlockBreakEventDispatcher 特例**：预检只跳过 `new BlockBreakEvent` + `callEvent`；`captureBlockBreakPlayer`（压栈）在 `BlockDropItemEvent` 有监听时必须保留（§2.4）；两者都空时整段跳过（此时捕获链消费端也不存在）。

---

## 六、P1-1：桥主体减配（有插件时也降本）

在 P0 之上，对有插件监听的场景继续削减桥内部成本（逐项按实测取舍）：

| 项 | 现状 | 改法 | 语义 |
|---|---|---|---|
| `DistValidate.isValid(level)` | 每事件一次（`cl == ServerLevel.class` 短路已快，其余类走 `SEEN_CLASSES` CHM + 父类链） | 保持（已是最小成本形态） | 不变 |
| `BlockPlaceEventDispatcher` 的 `fromBlockSnapshot` | 每事件重建 `CraftBlock`（含快照展开） | 与 P0-2 相同：`BlockPlaceEvent`/`BlockMultiPlaceEvent` 均无监听时首行 return（该 dispatcher 本就门控于玩家放置，改造后仅剩「放置方向捕获存在」的路径开销） | 不变 |
| dispatcher 的 `bridge$getBukkitEntity` | 每事件一次桥接实体查找 | 保持（插件需要实体对象） | 不变 |

> 原则：**有插件消费时，桥的构造是插件契约的一部分，不再削减**；P1-1 只处理「同一 dispatcher 的旁支事件类无人听」的残余，实质是 P0-2 的延展，不在 spark 归因显示瓶颈时不做额外工作。

---

## 七、P1-2：直接调用点空监听器预检 sweep（第二阶段）

- **目标**：§2.2 的直接调用点（不走 Forge 总线，spark 归因不到 `EventBus.post` 子树，但同属「无监听器也全量构造」）。
- **做法**：对每个调用点加

```java
if (BlockFormEvent.getHandlerList().getRegisteredListeners().length == 0) return <无监听器等价结果>;
```

- **必须逐个审计的返回值语义**（这是本项唯一的风险点，逐点记录）：

| 调用点 | 无监听器等价结果 | 现状调用方 |
|---|---|---|
| `callBlockFormEvent` | `return null`（调用方均判 `event != null && event.isCancelled()`，已核实 Liquid/Lava/ConcretePowder/ServerLevel/SnowGolem/ReplaceBlock/ReplaceDisk/LivingEntity 全部 null-safe） | `if (event != null && event.isCancelled()) { … }` |
| `callEntityDeathEvent` / `callPlayerDeathEvent` | 不可整体跳过——`EntityEventHandler.monitorLivingDrops` 还做 keepInventory 处理、经验、`EntityDropContainer` 掉落装饰（`event.getDroppedExp()` 等被读回） | 只跳过「构造 + callEvent」，保留其余控制流 |
| `callItemDespawnEvent` | 随 `ItemEntityEventDispatcher` 被 P0 门控，不需单独处理 | — |
| `callPlayerInteractEvent` 等交互类 | 需逐点审计（interact 返回值驱动 `useItemInHand`/`useInteractedBlock` 语义） | — |

- **顺序**：先做 `callBlockFormEvent` 一族（构造最重、调用最频、null 契约已核实），实测后再决定是否扩展到交互/死亡族。**不做的理由一律记录**（如 `callPlayerDeathEvent` 读回字段多，收益与审计成本不成比例时跳过）。

---

## 八、注册侧补充：模组向 NeoForge 注册监听器的成本与优化边界

> 本节约「事件注册」的另一侧：**mod 自己调用 `NeoForge.EVENT_BUS.register(...)` / `@EventBusSubscriber` 注册监听器**（与 §四 P0-1 的「Arclight 桥注册」相对）。结论先行：**注册侧大部分成本在启动期、一次性、不值得优化；运行期动态注册的真正成本是它对派发缓存的级联污染——只能检测提示、不能改语义**。

### 8.1 注册路径的事实（6.2.33 + FML loader-4.0.42 字节码核实）

**启动期（mod 加载，一次性）**：
- `@EventBusSubscriber` 由 FML `net.neoforged.fml.javafmlmod.AutomaticEventSubscriber` 在 mod 加载期扫描处理 → 调 `IEventBus.register(Class)`（静态订阅者）/ `register(Object)`（实例订阅者）。
- `registerClass`：`getMethods()`（分配 Method[]，含继承的 public 方法）→ 逐方法 `Modifier.isStatic` + `isAnnotationPresent(SubscribeEvent)`（每方法一次反射注解查询）。
- `registerObject`：同上，另加 `parentTypes()` **遍历全部父类/接口链** + 每层 `getDeclaredMethods()` + `HashMap<Key>` 去重（防止父类方法重复注册）。
- 每个监听器：`ASMEventHandler.create` → `IEventListenerFactory.create` → **ASM 生成一个处理器类**（ClassWriter + `ASMClassLoader.defineClass`，每监听器一个类）+ `ListenerListInst.register`（Semaphore + 优先级 ArrayList.add）+ `forceRebuild()` 级联。
- 量级：几百监听器 × 每类生成 ~几十 µs ≈ 总计几十 ms，占 ~10s 启动时间 **<1%——不值得优化**。

**运行期动态注册（`register()` 在 tick 中被调用）**：
- 同样的 ASM 生成 + Semaphore + **`forceRebuild` 级联**——但发生在运行期，真正的问题不是注册本身，而是**对派发缓存的污染**：一次注册使该事件类**及其全部子类**的缓存数组置 null，下一次 post 付重建（逐优先级 Semaphore + 父链合并 + toArray）。

**两条注册 API 的成本差异（已核实）**：
- `register(Object)` / `register(Class)`：每监听器 ASM 类生成；**保留注册对象后可 `unregister(对象)`**（P0-1 依赖这一点）。
- `addListener(Consumer)`：lambda 包装成 `IEventListener`（bootstrap #8/#9，**免 ASM 类生成**，注册更轻）；**但返回 void、无监听器句柄，无法按需注销**——所以 P0-1 的桥必须保持 `register(Object)` + 实例 + `unregister(实例)` 的形态。

### 8.2 可做的（全部「只统计/提示/实现约束」，不改任何语义）

| 项 | 内容 | 位置 |
|---|---|---|
| 运行期注册风暴检测 | `forceRebuild`/`buildCache` 计数 + 事件类 TopN（含子类级联次数估算）→ 日志提示「某 mod 在运行期高频注册/注销，正在持续污染派发缓存」 | 已规划的 P2-1 遥测，注册侧为扩展场景 |
| 「注册-触发」匹配遥测（可选） | 按事件类统计「注册监听器数」与 post 采样数 → 发现「注册了但从不触发」的监听器（提示管理员移除无用监听 mod）；**默认关**（post 计数对 75.5% 热点是税，只在调试期开） | P2-1 可选扩展 |
| P0-1 实现约束 | 桥按需注册必须用 `register(Object)` + 保留 dispatcher 实例 + `unregister(实例)`；不得用 `addListener`（无句柄无法注销） | §四落地时固化 |

### 8.3 明确不做

| 项 | 为什么不做 |
|---|---|
| 改 `ASMEventHandler` 生成机制 / 注册批处理 / 延迟注册 / 限流 | eventbus 本体；注册是同步 API，mod 期望「注册后下一次 post 立即收到」，注册顺序可观察（优先级数组序）——任何合并/延迟/限流都改变 mod 可观察语义 |
| 为「从不触发」的监听器跳过派发 | 监听器内部逻辑是黑盒，无法判定「不关心」；只能统计提示 |
| mod 自定义事件的构造与 post（如 QuarryPlus 自发 `BlockEvent.BreakEvent`） | 构造与 post 都在 mod 代码里，PRTS 不可动；但这类事件类同样被 `EventSubclassTransformer` 处理（注入 static `ListenerList`），派发与 vanilla 事件同为快路径——「mod 定义事件 = 慢派发」不成立 |

### 8.4 高频恒定事件族：tick 事件的监听器税（很多 mod 注册 tick 事件时）

**事实（已核实）**：
- **每 tick 恒定 post 数**（与服务器负载无关）：`ServerTickEvent.Pre/Post` ×2（`MinecraftServer.tickServer` 头尾，vanilla patched 代码直接调 `EventHooks.fireServerTickPre/Post`）+ `LevelTickEvent.Pre/Post` × 维度数 ×2（3 维度 = 6 次）≈ **8 次/tick**。
- 每次 post = 1 次事件构造 + N 监听器 ×（canceled 读 + 接口调用 + 主体进入）。**单监听器派发税 ~10–20ns**（§1.1 已核实：无锁、缓存数组）；100 个 tick 监听器 ≈ 1–2µs/tick，相对 50ms tick **可忽略**。**大头永远是监听器主体（mod 代码每 tick 做的维护），不是总线**。
- **PRTS 控制着 `LevelTickEvent` 的两个触发点**：`DimensionTickManager` 的 `PRE.fire`（主线程，并行阶段前）与 `POST.fire`（主线程，并行阶段后）——**Pre/Post 都在主线程 fire**，mod 的 tick 监听器线程假设与 vanilla 一致；worker 线程上发生的是「tick 期间产生的世界事件」（如 QuarryPlus 机器 tick 里自发 `BlockEvent`），不是 tick 事件本身。

**可做（顺带项，不单独立项）**：
- PRE/POST 包装加 O(1) 无监听器短路：`arclight$hasListeners(LevelTickEvent.Pre.class)` 为 false 时跳过构造与 post（公共底座见 §8.5）。收益 ~0.6µs/tick（无监听器服）——**量级可忽略，作为 §8.5 短路模式的顺带实现**，不做单独 A/B。
- `ServerTickEvent` 触发点在 vanilla patched 代码，PRTS 可 mixin @Redirect 短路——**同样收益可忽略，不做**（除非实测 tick 事件构造在 profile 中可见）。

**该做的（归因优先）**：
- spark 归因 `ServerTickEvent` / `LevelTickEvent` **监听器主体**占比：若生产服多个 mod 的 tick 监听器主体显著（每 tick 固定支出），方向是**对特定 mod 定向路由/节流**（既有机制：`main-thread-entity-force` / `be-main-thread-force` / `ClassAffinityLedger`），**不是动事件总线**。

**明确不做**：
- 跳过「本 tick 无事可做」的监听器（黑盒不可判定；mod 侧自做节流属 mod 的事）。
- tick 事件异步化 / 延迟 / 合并（同步语义：mod 期望事件处理完 tick 才继续；合并改变每 tick 时序——审计红线）。

### 8.5 其余高频事件触发点审计（vanilla patched 代码全量扫查）

> 对 `minecraft-merged-mojang-patched.jar`（21.1.248）中 `EventHooks`/`CommonHooks` 的全部调用点按类扫查（javap 逐类 grep），对照 §8.4 与 §四/§五（桥事件）后，**新发现两个值得立项的短路点 + 一个可选点**。全部短路的基础设施相同：`arclight$hasListeners(Class<? extends Event>)`（EventBus 只读 bridge，见下）。

**扫查结果表**（只列每 tick / 每高频操作触发的）：

| 事件 | 频率（已核实） | 触发点 | 短路可行性 | 收益量级 |
|---|---|---|---|---|
| **`EntityTickEvent.Pre/Post`** | **每实体每 tick ×2**（频率之王） | `ServerLevel.tickNonPassenger`（`EventHooks.fireEntityTickPre/Post` 包住 `entity.tick()`，Pre 取消可跳过整个 tick）——**PRTS 区域并行仍走此包装**（`RegionTickManager:864` `level.tickNonPassenger(entity)`，worker 线程） | ✅ 无监听器时 `Pre.isCanceled()` 恒 false → `entity.tick()` 照跑，短路逐位等价 | ~50–150ns/实体/tick；1000 实体（刷怪塔）≈ 100–300µs/tick（**0.2–0.6% tick 预算，全部候选中最高**） |
| `PlayerTickEvent.Pre/Post` | 每玩家每 tick ×2 | `Player.tick` 头尾（主线程） | ✅ 同款 | 13 人 ≈ 26 次/tick ≈ 3µs/tick——可忽略，并入 P1-3 同款模式但**不单独立项** |
| **`BlockEvent.NeighborNotifyEvent`** | 每次 `updateNeighborsAt` 调用 | `Level.updateNeighborsAt`（**NeoForge 在 vanilla 空壳上 fire 事件 + 丢弃结果**——`isCanceled()` pop 掉，对邻居更新无任何影响）；调用者：`PistonBaseBlock`×3、`RedStoneWireBlock`×5、`ServerLevel`×2 等 | ✅ **语义最安全**：结果本就被丢弃，无监听器时连 fire 都可跳过（连「等价判定」都不需要） | 红石/活塞密集场景（机械服）每 tick 数次 ×~150ns（EnumSet 分配 + getBlockState + 构造 + post） |
| `MobSpawnEvent` 系（`checkSpawnPosition`/`getPotentialSpawns`） | 每刷怪尝试 ×2 | `NaturalSpawner`（vanilla patched） | ⚠️ 短路需保留「无监听器 = 事件返回默认（不取消）」语义——短路直接走原逻辑即可，但 `checkSpawnPosition` 返回值参与刷怪判定，审计成本略高 | 刷怪密集服可观（PRTS mob_spawning 已降尝试数，事件短路为叠加）——**先 spark 归因，列为可选（P2-3）** |
| `fireItemPickupPre/Post` | 每物品拾取 ×2 | `ItemEntity.tick` | ✅ 同款但收益小（拾取中频） | 不立项（归入统一模式，不做单独项） |
| `LivingDamageEvent`/`LivingAttackEvent` 系 | 每次伤害 | `LivingEntity`（`CommonHooks.onLivingDamagePre` 等） | ❌ 返回值参与伤害计算（Pre 可改伤害/取消），短路须精确保留语义——**不建议**（伤害路径 mod 依赖最多） | 风险 > 收益 |

**P1-3：`tickNonPassenger` 的 `EntityTickEvent` 短路（立项）**
- 做法：mixin `ServerLevel.tickNonPassenger` @Redirect `EventHooks.fireEntityTickPre/Post` → PRTS 包装：`arclight$hasListeners(EntityTickEvent.Pre.class)` 为 false 时返回**缓存默认 `Pre` 实例**（static final，entity 传 null——无监听器时无人读取该字段，调用方只读 `isCanceled()` 恒 false）并跳过 Post；有监听器时原样转发。
- 语义论证：无监听器时 `Pre.isCanceled()` 恒 false → `entity.tick()` 照跑 → 与短路逐位等价；监听器存在时行为与现状逐位一致。**零事件数量/时机变化**（事件照发，只是无监听器时「发」本身消失——发到空总线本就无观察者）。
- 让位：`EntityTickEvent` 监听器存在即失效（length > 0 判断天然让位），无需 mod 检测。
- 配置：`event-shortcircuit.entity-tick-event.enabled`（默认开，零语义风险，同 entityspatial 先例）+ 遥测 `[event-shortcircuit]`（`skippedPre`/`skippedPost`/`forwardedPre`/`forwardedPost`，挂 `PRTSFeatures.tick`）。
- 兼容注意：**PRTS 区域并行下该短路在 worker 线程执行**——`arclight$hasListeners` 是纯读（volatile 数组 + length），无锁，线程安全。

**P1-4：`updateNeighborsAt` 的 `NeighborNotifyEvent` 短路（立项，零语义风险）**
- 做法：mixin `Level.updateNeighborsAt` @Redirect `EventHooks.onNeighborNotify`：`arclight$hasListeners(BlockEvent.NeighborNotifyEvent.class)` 为 false 时直接返回（跳过 EnumSet 分配 + getBlockState + 事件构造 + post）。
- 语义论证：**原代码就把 `isCanceled()` 结果丢弃**（字节码核实：`invokevirtual isCanceled; pop`）——事件对后续逻辑零影响；无监听器时跳过与现状**完全相同**（连事件构造都不发生）。监听器存在时照 fire（`alternate_current` 等红石 mod 监听时自动让位）。
- 配置：并入 `event-shortcircuit` 段（`neighbor-notify-event.enabled` 默认开）+ 遥测计数。
- 注意：只短路 `updateNeighborsAt` 这一个方法；`onNeighborNotify` 在别处无其它调用点（已扫查）。

**P2-3：`MobSpawnEvent` 系短路（可选，先 spark 归因）**
- 自然生成尝试已被 PRTS mob_spawning 优化削减（mobcap 强制/类别间隔），事件构造+post 是叠加成本；刷怪密集服 spark 若显示 `NaturalSpawner` 子树显著，按 P1-3 同款模式短路 `checkSpawnPosition`/`getPotentialSpawns`（短路直接走原逻辑，保留「无监听器 = 默认结果」）。

**公共底座：`arclight$hasListeners(Class)`**
- EventBus mixin 加只读查询方法：`event.getListenerList().getListeners(busID).length > 0`（busID 经 `@Shadow`/Accessor 取；`NO_LISTENERS` 空数组常量 → length==0 判定成立；缓存失效时触发 buildCache——注册/注销后第一次调用，低频可接受）。
- 与 §8.4 的 PRE/POST 短路共用；**只读、无锁、不改任何语义**（与 `Event.isCanceled()` 同类的查询方法）。

---

## 九、P2：明确不做 + 让位 + 上游跟踪

### 8.1 明确不做（与审计红线一致）

| 项 | 为什么不做 |
|---|---|
| 改 `EventBus.post` 派发本体 | 兼容面最大；6.2.33 已缓存、零锁；绕过派发链会破坏 `trackPhases`/异常处理/`isCanceled` 短路语义；收益仅 ~10-30ns/事件 |
| 事件对象池 | mod 契约禁止持有事件引用，但现实存在违例 mod；池化后引用失效即崩。PRTS 自己的 Bukkit 桥事件池同理（插件也可能持有）——列为研究项，默认不做 |
| 事件降频 / 合并 / 延迟 | 审计红线：`BlockEvent` 数量与时机是 mod 契约，绝不削减 |
| 吞掉 `BlockEvent` 或改其构造时机 | 同上 |
| 给「无 sculk」的 GameEvent 预检（审计 §5.5） | 与本方案不同课题（vanilla 派发链），沿用审计「先 spark 归因」结论，不并入本文 |

### 8.2 让位与检测

- 无已知同类优化 mod（menu/poi/collision 均有核对先例，eventbus 无同类）；如未来出现（eventbus 换装/加速类），桥优化沿用 `@LoadIfMod(ModCondition.ABSENT)` 让位机制。
- **动态注册风暴检测（P2-1 遥测）**：mixin `ListenerListInst.forceRebuild`/`buildCache` 统计重建次数/秒与事件类 TopN，`[event-bridge]` 日志输出。**只统计不拦截**（`thread-policy` 同款哲学）——用于发现「每 tick 注册/注销监听器」的 mod，为未来决策提供数据，不改变任何语义。
- 上游跟踪：eventbus 6.2.33 → 后续版本（若引入更省锁的缓存/更快派发），随 NeoForge 升级自然获得；升级时重跑本文 §一 的核实步骤即可。

---

## 十、验证计划

### 10.1 功能回归（语义逐位一致，两组对照）

| 场景 | 断言 |
|---|---|
| 玩家破块（有/无插件两组） | 事件计数逐位一致：插件侧监听器收到数与现状相同；无插件组 `[event-bridge] forwardedEvents=0` 且方块破坏、掉落、经验、客户端同步与现状完全一致 |
| QuarryPlus 整列破块 | 事件计数断言：mod 侧 `BlockEvent.BreakEvent` 一个不少（对拍调试计数）；插件监听 `BlockBreakEvent` 时收数与现状一致；无插件组桥开销归零 |
| 掉落链（`BlockDropItemEvent` 插件在听、`BlockBreakEvent` 无人听） | 掉落事件照发、`isDropItems` 语义不变（P0-1 门集合验证） |
| RTSbuilding 破坏任务 | `DestructionTaskState.copyTags` 事件流不受影响（事件照发，只降桥） |
| 物品消失 / 末影珍珠 / 驯服 / 死亡 / 流体成块 | 各对应 Bukkit 事件有/无插件两组对拍 |
| `/reload` 与插件 enable/disable 风暴 | dispatcher 注册/注销计数正确、无泄漏（重复 reload 后 `[event-bridge] dispatcherRegister/Unregister` 配对）；注销窗口内桥多转发不漏事件 |
| `eager-registration: true` | 与现状完全一致（旧路径保留，A/B 验证两条路径逐位一致） |

### 10.2 性能对照（测试服实机，复用既有协议）

- 环境：`prts-test`（NeoForge 1.21.1 + PRTS 构建，无插件 / 挂载测试插件两组）。
- 场景：QuarryPlus 整列破块压测（无插件腿）+ 玩家挖掘；`[event-bridge]` 遥测对拍 `forwardedEvents`/`skippedEvents`；spark 采样 `EventBus.post` 子树，目标从 75.5% 显著回落（预期：无插件服回落至 mod 监听器主体 + 构造为主）。
- 与 `menubench` 同协议：交叉 A/B、全预热、取中位；记录 6 次以上会话复测。

### 10.3 线程回归

- `thread-policy: enforce` 测服 + region parallel 下：worker 派发路径（QuarryPlus 机器 tick）无新增违规；`SimplePluginManagerMixin_DimParallel` 放行语义不变。
- 插件 enable/disable 与 worker 派发并发窗口：`/reload` 风暴下无异常/无事件丢失断言。

---

## 十一、遗留与风险

- **实施前待复核项**（静态证据已具备，落地时逐条确认）：① `BlockDropItemEvent` 门集合是否还需包含其它消费端（`getBlockDrops()` 的其它调用方，已静态确认仅 BlockMixin/ServerPlayerGameModeMixin）；② `ItemDespawnEvent` 由 `callItemDespawnEvent` 构造的类名与门集合一致性；③ vendored `CraftEventFactory` 版本与 `SimplePluginManager` mixin 挂点（registerEvent 入口先于 HandlerList.register 的可行性，§4.3 缓解）。
- **已知差异**：桥监听器相对顺序从「mod 加载期」移后到「首个插件 enable」（§4.4），提供 `eager-registration` 逃生门；预期无人依赖，但需在验证计划 §10.1 的「顺序敏感场景」中显式回归。
- **不做项**（§8.1）：事件池、事件降频、总线本体改造——收益与兼容风险不成比例。
- **后续课题**（与本文无关，沿用审计既有结论）：GameEvent 派发链（§5.5，先 spark 归因）、漏斗空转检测（§五④）、`findClosest` POI 剪枝（§5.1 二期）。

---

## 十二、明确事项清单（Action Items，按序执行）

> 本文档只给方案，不给代码。以下每项均为**可独立验收的行动事项**：改动位置精确到类/方法，验收标准写清「满足什么算完成」。顺序建议按编号执行（A-01→A-13 为主线；A-14 之后为并行/可选）。执行前先做 A-00 基线采样。

### 阶段 0：基线（先行）

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-00** | 生产服 spark/JFR 基线采样：`EventBus.post` 子树（含 `fireEntityTickPre/Post`、`tickNonPassenger`、`NaturalSpawner` 子树）与 `[event-bridge]` 式转发计数 | 生产服/测试服，spark + JFR | 记录改造前各子树占比与 TPS/MSPT 中位，作为 A/B 对照基线；同时确认 §8.5 扫查表的频率假设（实体数、红石/活塞操作数） |

### 阶段 1：P0 桥按需注册 + 预检（主线，收益最大）

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-01** | 实施前复核三项（§十一①~③）：① `captureBlockBreakPlayer` 压栈 context 的消费方清单（静态已确认仅掉落链，落地再核）；② `ItemDespawnEvent`/`EntityTameEvent`/`PlayerTeleportEvent` 的构造类名与门集合一致性；③ `SimplePluginManager.registerEvent` 入口是否先于 `HandlerList.register`（§4.3 窗口缓解的挂点可行性） | `ArclightCaptures` / `CraftEventFactory` / `SimplePluginManager`（vendored 源） | 三项全部结论落文档；若 ③ 不成立，改用「register 后置 + P0-2 预检兜底」并更新 §4.3 |
| **A-02** | 实现 Bukkit 事件类监听器计数：mixin `SimplePluginManager` 的 `registerEvent`/`registerEvents`/`unregister*` 路径，维护「Bukkit 事件类 → 已注册监听器数」 | `arclight-common` mixin（与 `SimplePluginManagerMixin_DimParallel` 同文件族） | 插件 enable/disable 与 `/reload` 后计数与 `HandlerList.getRegisteredListeners().length` 一致（断言脚本）；计数仅主线程访问 |
| **A-03** | 实现桥注册表（替代 `registerAllEventDispatchers`）：按 §4.2 门集合，0→1 时 `register(dispatcher 实例)`、1→0 时 `unregister(实例)`；`PRTSCommandDispatcher` 保持常驻；`eager-registration: true` 时回退旧行为（启动即全注册） | `ArclightEventDispatcherRegistry`（重构）+ `ArclightMod` 构造 | 无插件时 `NeoForge.EVENT_BUS` 上无 5 个桥 dispatcher（反射/遥测断言）；有插件监听时与现状逐位一致（§10.1 对拍）；`eager-registration` 开关 A/B 一致 |
| **A-04** | 5 个桥 dispatcher 方法头加 O(1) 预检（P0-2）：对应 Bukkit 事件 `getHandlerList().getRegisteredListeners().length == 0` 时跳过构造与 `callEvent`；`BlockBreakEventDispatcher` 特例：`BlockDropItemEvent` 在听时保留捕获压栈 | 6 个 dispatcher 文件 | 无插件时 `[event-bridge] skippedEvents` 增长且零事件构造（分配断言）；有插件时 `forwardedEvents` 与现状一致 |
| **A-05** | 配置段 + 遥测：`prts-features.yml` 加 `event-bridge:` 段（`on-demand-registration.enabled` 默认开 / `eager-registration` 默认关 / `telemetry-enabled` 默认开）；新增 `EventBridgeStats` 挂 `PRTSFeatures.tick` | `PRTSFeaturesConfig` / `PRTSFeatures` / 新 `EventBridgeStats` | 配置模板注释齐全；`[event-bridge]` 日志按既有 Stats 格式输出 `dispatcherRegister/Unregister/forwardedEvents/skippedEvents/capturedOnly` |

### 阶段 2：P1 短路（共享底座先行）

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-06** | 公共底座 `arclight$hasListeners(Class<? extends Event>)`：mixin `EventBus` 加只读查询（`getListenerList().getListeners(busID).length > 0`，busID 经 @Shadow/Accessor） | 新 mixin（EventBus） | 对 6.2.33 字节码行为零影响（仅新增查询方法）；缓存失效（注册后）首次调用正确触发 buildCache 且无死锁 |
| **A-07** | P1-3 `EntityTickEvent` 短路：mixin `ServerLevel.tickNonPassenger` @Redirect `EventHooks.fireEntityTickPre/Post`，无监听器时返回缓存默认 `Pre` 实例（entity 传 null）并跳过 Post | 新 mixin（`ServerLevel`） | §10.1 事件计数对拍：有监听器时与现状逐位一致；无监听器时实体照常 tick（移动/寻路/掉落回归）；worker 线程下 0 异常；`[event-shortcircuit]` 计数正确 |
| **A-08** | P1-4 `NeighborNotifyEvent` 短路：mixin `Level.updateNeighborsAt` @Redirect `EventHooks.onNeighborNotify`，无监听器时直接返回 | 新 mixin（`Level`） | 红石/活塞实机回归（§10.1）：有/无监听器两组行为一致；`alternate_current` 等红石 mod 在场时短路自动失效（length>0） |
| **A-09** | §8.4 顺带项：`DimensionTickManager` 的 `PRE.fire`/`POST.fire` 包装加 `arclight$hasListeners(LevelTickEvent.Pre/Post.class)` 短路 | `DimensionTickManager`（`setLevelTickCallbacks` 处替换包装） | 无监听器时 LevelTickEvent 构造为零；有监听器时与现状一致；不做单独 A/B（收益量级已论证） |
| **A-10** | 短路配置段 + 遥测：`event-shortcircuit:` 段（`entity-tick-event.enabled` 默认开 / `neighbor-notify-event.enabled` 默认开 / `telemetry-enabled`）+ 新 Stats | `PRTSFeaturesConfig` / `PRTSFeatures` / 新 Stats | 模板注释齐全；`[event-shortcircuit]` 输出 `skippedPre/skippedPost/forwardedPre/forwardedPost` 与 `neighborNotifySkipped` |

### 阶段 3：P1-2 直接调用点 sweep

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-11** | `callBlockFormEvent` 一族 8 个调用点加预检（§七，null 契约已核实） | `LiquidBlockMixin` / `LavaFluidMixin` / `ConcretePowderBlockMixin` / `ServerLevelMixin` / `SnowGolemMixin` / `ReplaceBlockMixin` / `ReplaceDiskMixin` / `LivingEntityMixin` | 无监听器时流体成块/方块形成路径零事件构造（分配断言）；有监听器时行为逐位一致（含取消语义） |
| **A-12** | 交互/死亡族逐点审计并落地或记录不做理由（`callPlayerInteractEvent`、`callEntityDeathEvent`/`callPlayerDeathEvent`） | `CraftEventFactoryMixin` / `EntityEventHandler` | 每个调用点一行结论（做/不做+理由）落文档；做的点通过 §10.1 对拍 |

### 阶段 4：P2 遥测 / 可选 / 让位

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-13** | P2-1 重建归因：mixin `ListenerListInst.forceRebuild`/`buildCache` 计数（次/秒 + 事件类 TopN + 子类级联次数），输出到 `[event-bridge]` | 新 mixin（`ListenerList`） | 只统计不改语义（`thread-policy` 同款哲学）；日志可发现「运行期高频注册/注销」mod 类名 |
| **A-14** | （可选）「注册-触发」匹配遥测：按事件类统计注册监听器数 + post 采样计数，**默认关** | 新 mixin（`EventBus.post` 采样） | 仅调试期开启；输出「注册了但从不触发」的监听器清单 |
| **A-15** | P2-3 `MobSpawnEvent` 系：A-00 基线确认 `NaturalSpawner` 子树占比后，决定是否按 P1-3 同款短路 `checkSpawnPosition`/`getPotentialSpawns` | `NaturalSpawner` mixin（复用 `arclight$hasListeners`） | 先有 spark 数据再立项；落地走「默认开/遥测/对拍」闭环 |
| **A-16** | 让位机制备忘：若未来出现 eventbus 换装/加速类 mod，桥与短路注入统一挂 `@LoadIfMod(ABSENT)` 让位 | 各新 mixin 类 | 文档登记，无实施动作 |

### 阶段 5：验证（每完成一个阶段跑一次）

| # | 事项 | 位置 | 验收标准 |
|---|---|---|---|
| **A-17** | §10.1 功能回归全表（事件计数断言 / QuarryPlus 整列破块 / 掉落链 / reload 风暴 / eager-registration A/B） | 测试服 + RCON | 全 PASS，计数逐位一致；失败项按 §3.2 状态机登记 |
| **A-18** | §10.2 性能对照：QuarryPlus 破块压测（有/无插件两腿）+ spark 复测 `EventBus.post` 子树 | `prts-test` | 与 A-00 基线对比记录；无插件腿 `EventBus.post` 子树显著回落（预期至 mod 监听器主体+构造为主） |
| **A-19** | §10.3 线程回归：`thread-policy: enforce` + region parallel + `/reload` 风暴 | 测试服 | 0 违规 / 0 异常 / 0 事件丢失 |

> **完成线定义**：A-00 至 A-10 全部验收 PASS 且 A-17/A-18 对拍通过，即本文案 P0+P1 主体完成；A-11~A-16 为增量，可按生产服实测数据取舍。
