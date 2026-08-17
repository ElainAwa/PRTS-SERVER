# P1-2 直接调用点 sweep + P2 遥测 —— 审计与落地记录（2026-08-17）

> **性质**：`docs/2026-08-17-event-bridge-optimization-plan.md` 第二阶段（A-11~A-12）与 P2-1（A-13/A-14）执行记录。
> **基线**：分支 `1.21.1-Multithreading`，HEAD `07359434`（含 PR #1 事件桥优化）。
> **兼容红线**：事件数量/时机零变化；每个优化有配置逃逸阀 + 遥测。

---

## A-11 ✅ 已落地：`callBlockFormEvent` 一族 P1-2 预检

**做法**：在 `ArclightEventFactory.callBlockFormEvent`（**统一漏斗**）加空监听器预检，一处覆盖全部 8 个调用点：

| 调用点 | 类 |
|---|---|
| 流体成块（水源扩展） | `LiquidBlockMixin` |
| 岩浆成块（水遇岩浆→圆石/黑曜石） | `LavaFluidMixin` |
| 混凝土粉末入水 | `ConcretePowderBlockMixin` |
| 落雪/雪傀儡 | `SnowGolemMixin` |
| 方块实体/玩家交互侧 | `ServerLevelMixin` / `LivingEntityMixin` / `CraftEventFactoryMixin` |
| 附魔替换（Replace 系） | `ReplaceBlockMixin` / `ReplaceDiskMixin` |

- **预检形态**：`(entity == null ? BlockFormEvent : EntityBlockFormEvent).getHandlerList().getRegisteredListeners().length == 0` → `return null`（O(1) volatile 数组读，同 P0-2 dispatcher 预检）。
- **null 契约**：8 个调用方全部 `event != null && event.isCancelled()`，已逐点核实 null-safe（与计划 §七一致）。
- **配置逃逸阀**：`event-shortcircuit.block-form-event.enabled`（默认 true，`PRTSFeaturesConfig.eventShortcircuitBlockFormEnabled`）。
- **遥测**：`[event-shortcircuit]` 新计数器 `skippedBlockForm` / `forwardedBlockForm`（`EventShortcircuitStats`）。
- **收益依据**：流体成块在刷石机/刷冰机/水冷刷怪场景每 tick 高频触发；无插件时整条「构造 + callEvent」归零。

## A-12 🔍 已逐点审计，结论：本阶段不做，理由记录

| 族 | 调用方 | 审计结论 | 理由 |
|---|---|---|---|
| `callEntityDeathEvent` | `CraftEventFactoryMixin.callEntityDeathEvent`（@Overwrite vendored） | **不做** | 调用方读回 `event.getDroppedExp()` + 遍历 `event.getDrops()` 在世界中 dropItem；跳过构造必须重构该 5 行方法 + 保持默认值语义；击杀频率低（每 kill），收益与审计/回归成本不成比例。可降频的只有排队发空的 `callEvent`，量级更小。 |
| `callPlayerDeathEvent` | `EntityEventHandler:51` | **不做** | 调用方依赖 event 的 deathMessage/keepInventory/keepLevel/掉落/经验多字段写回，无法整体跳过（计划 §七已判断）。死亡低频。 |
| `callPlayerInteractEvent*`（~15 调用点） | `ServerPlayerGameModeMixin` / `ServerGamePacketListenerImplMixin` / Boat/Dripleaf/Farm/PressurePlate/RedStoneOre/Sculk*/TripWire/TurtleEgg/WeightedPlate 等 | **不做** | 返回值（`InteractionResult`/`isCancelled`）直接驱动 `useItemInHand`/`useInteractedBlock`/`shouldCancelInteract` 语义；GET/POST 派生类（PlayerInteractEntityEvent 等）独立 HandlerList；交互受玩家操作频率限制（非每 tick 风暴）。改为：**待生产服 spark 归因**——若 `CraftEventFactory.callPlayerInteractEvent` 子树成为实测热点再逐点立项。 |

> 覆盖边界记录：`ConcretePowderBlockMixin.getStateForPlacement`/`updateShape` 的直发路径**不在 A-11 漏斗内**（不经 `callBlockFormEvent`）——频率低（放置/形状计算时），未处理，列为后续候选。

## A-13 ⛔ P2-1 重建归因：**阻塞不可行**，理由记录

计划稿 §八 的 A-13 需要 mixin `net.neoforged.bus.ListenerListInst.forceRebuild`/`buildCache` 计数。**不可行依据**：`ListenerList`/`ListenerListInst` 与 `EventBus` 同属 `bus-8.0.5.jar`（MC-BOOTSTRAP 模块层），mixin 无法注入（P0-2 落地时实机验证 `EventBus` 注入抛 ClassCastException，`EventBusQuery` 因此改用反射 + 缓存 Method）。`forceRebuild`/`buildCache` 计数必须注入 bus 类——无 Java agent 下没有注入点。
替代方案评估：无侵入的定时反射采样无法分离「重建痕迹」（重建只在 post 时按需发生，查询本身会触发 buildCache，污染观测）；收益（发现「运行期高频注册」mod）与工程成本不成比例。**留待有 agent 注入方案或下次主分支升级重估。**

## A-14 ⛔ 「注册-触发」匹配遥测：不做，理由记录

计划稿 §八 标注默认关、仅调试期启。post 采样计数会给刚优化的热路径（`EventBus.post`）添加每事件开关开销——与 P0 优化目标直接相悖；且需要按事件类维护注册数 ↔ 采样数映射，复杂度高。**不做**。

## 验证计划

- 构建 `:bootstrap:neoforgeJar` → 部署 prts-test → 启动。
- 冒烟回归：RCON `/setblock` 构造「水 ↑ 岩浆」圆石生成场景，确认 `[event-shortcircuit] skippedBlockForm` 增长（无插件 → 短路生效），服务器无异常。
- 有插件回归（必要时）：挂 TestListener 监听 `BlockFormEvent`，确认 `forwardedBlockForm` 增长且方块仍正常形成（取消语义保留）。

---

## 2026-08-17 实机复验（F-1 已解决，A-11 已端到端验证）✅

> 探针方法：`callBlockFormEvent` 入口临时打点（`-Dprts.p12probe` 门控）+ `-Dmixin.debug.export=true` 导出运行时转换类 + RCON 触发场景。验证完成，探针已移除，最终构建已部署（`bootstrap/build/libs/` 16:38，预检在、探针不在）。

**结论（逐条钉死）：**

1. **运行时（post-mixin）`CraftEventFactory.handleBlockFormEvent` 5 参确实是 @Overwrite 版**：`.mixin.out` 导出的运行时类 javap 显示其 body 调 `ArclightEventFactory.callBlockFormEvent`（全类 2 处引用）。**mixin 正常生效**——此前基于 class 文件的 javap 结论是错的（class 文件是转换前状态，mixin 是内存态转换）。
2. **水遇岩浆/流体成型在本 fork 根本不发 Bukkit 事件**：运行时 NMS（`server-1.21.1-...-srg.jar`）`LiquidBlock.shouldSpreadLiquid` 字节码 **0 处 BlockFormEvent/handleBlockFormEvent 引用**——纯 vanilla 直接 setBlock，无 CraftBukkit 转换补丁（`.gradle` 缓存里的 `nms.old` 源码与运行时不是同一代 patch）。实测：水+岩浆源相邻 → 黑曜石生成 ✓，但 `callBlockFormEvent` 探针 0 行 → **该路径无事件可优化**。
3. **A-11 端到端验证通过**：RCON summon 僵尸×3 + 凋灵 → 凋灵玫瑰成型（`createWitherRose` → 5参 @Overwrite → 漏斗）→ 探针打印 `enter` + `SKIPPED`，遥测 **`skippedBlockForm=6`**。**所有真正发 BlockFormEvent 的路径（降雪/结冰、雪傀儡、凋灵玫瑰、霜行者等附魔、混凝土 onLand）都经过漏斗，全部被 A-11 覆盖。**
4. 之前 4 次触发 `skippedBlockForm=0` 的场景（黑曜石、水流、混凝土、霜行者）全部属于"不发事件"或"未达成触发条件"，不是优化失效。

**对计划稿假设的修正**：§2.2 假设「流体成块高频、每 tick 构造事件」在本 fork 不成立——流体成型是 vanilla 直写方块，零 Bukkit 事件开销。A-11 的真实收益面 = 所有走 `callBlockFormEvent` 的成型事件（降雪/霜行者/凋灵玫瑰等），已在运行时验证生效。

### 覆盖边界（记录）

- `ConcretePowderBlockMixin.getStateForPlacement`/`updateShape` 直发（不经漏斗）——放置/形状计算时低频，未处理，列后续候选。
- 运行时黑曜石/圆石成型无事件（见上）——无需优化。