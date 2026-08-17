# P3 落地：容器菜单广播预检短路 —— 实现 + 性能对照表（2026-08-16 晚，测试服实机）

> **文档性质**：AI 创作。对应审计文档 `2026-08-16-vanilla-server-highload-hotspots-audit.md` §阶段5·5.6（P3「先实测归因」）的落地记录；§5.7（Tab list）、§5.8（弹射物 clip）同期完成实测归因，结论见 §四。
> **审查基线**：分支 `feature/lightopti`，HEAD `73890b60` 之上新增本功能。
> **验证环境**：`prts-test` 测试服（NeoForge 1.21.1 + PRTS v1.0.36-Multithreading 构建，无 mod），RCON 控制。
> **兼容红线**：语义逐位一致（预检是原版 diff 的提前等价物，不是脏槽跟踪——mod 直写容器同样被同一 diff 捕获，零漏检）；配置逃逸阀（默认关）；遥测可观测。

---

## 一、实现摘要（§5.6 本体：全等预检短路）

### 1.1 目标
1.21.1 的 `AbstractContainerMenu.broadcastChanges()`（`ServerPlayer.tick` 每 tick 对每个打开中的菜单调用）**全量遍历所有槽位**：

```
for (int i = 0; i < slots.size(); i++) {
    ItemStack stack = slots.get(i).getItem();
    Objects.requireNonNull(stack);
    Supplier<ItemStack> supplier = Suppliers.memoize(() -> stack.copy());  // 每槽每 tick 2 个对象分配
    this.triggerSlotListeners(i, stack, supplier);      // 内部 lastSlots diff，无变化即 return
    this.synchronizeSlotToRemote(i, stack, supplier);   // 内部 lastSlots diff + 发包
}
this.synchronizeCarriedToRemote();                       // remoteCarried diff（已有）
for (int i = 0; i < dataSlots.size(); i++) { ... }       // checkAndClearUpdateFlag（已有 diff）
```

即使菜单完全静止（玩家开着箱子不动），每槽每 tick 仍付：`getItem` + `requireNonNull` + **memoize lambda 分配** + 两次 `lastSlots` diff 调用。AE2 风格几百槽菜单 × 20 tick/s = 每 tick 数百个对象分配，纯浪费。

### 1.2 做法（预检短路，非脏槽跟踪）
在 `broadcastChanges` HEAD 用**与原版逐条等价的判定**做全等预检：

```
预检 = dataSlots 值缓存比较（等价 checkAndClearUpdateFlag，无清除副作用，先查——数量少、快速失败）
     → slots：lastSlots.get(i) vs slots.get(i).getItem()（与原版 triggerSlotListeners 完全相同）
     → carried：carried vs remoteCarried（与原版 synchronizeCarriedToRemote 完全相同）
全等 → 原版循环必然无动作 → 整个方法短路（ci.cancel）
不等 → 走原版（原版 diff 照常，语义不变）
```

- **为什么不是脏槽跟踪**：文档 §5.6 要求的「覆盖 mod 直接写容器（`Container.setItem` 绕过 menu）的路径」——脏槽标记做不到（`Slot.set` 不经过），而预检是**全量 diff 的提前等价物**：mod 直写容器后，`lastSlots.get(i)` 与 `slots.get(i).getItem()` 不等，预检与原版都会发现并广播。零漏检。
- **dataSlots 值缓存**：原版 `checkAndClearUpdateFlag` 有清除副作用（更新 `prevValue`），预检不能调它。用菜单实例字段缓存「上次广播后的 `dataSlot.get()`」，走原版后 RETURN 刷新。语义等价（原版 flag = `get() != prevValue` 纯值比较，无 set 标志）。
- **失败冷却（cooldown）**：预检失败 → 接下来 20 次 broadcastChanges 直接走原版（不预检）。否则「每 tick 都变化」的菜单（AE2 终端刷新等）每 tick 付「预检全扫失败 + 原版全扫」= 2× 遍历。冷却把密集变化场景压回原版成本；静止恢复后最多 20 tick（1 秒）白付原版成本即重新启用预检。语义不变（原版路径本身做同样的 diff）。
- **`suppressRemoteUpdates` 让位**：客户端批量更新窗口内完全走原版（原版 carried 同步有该短路，最保守）。

### 1.3 改动文件
| 文件 | 改动 |
|---|---|
| `mixin/.../menubroadcast/AbstractContainerMenuMixin_MenuBroadcast.java` | 新增：HEAD 预检短路（dataSlots 前置 + slots + carried）+ cooldown + RETURN 缓存刷新 |
| `optimization/general/menubroadcast/MenuBroadcastStats.java` | 新增：`skippedBroadcasts` / `fullBroadcasts` / `cooldownHits` / `slotsChecked` |
| `compat/prts/PRTSFeaturesConfig.java` | `menu-broadcast.enabled`（**默认关**）+ `telemetry-enabled` + 模板注释 |
| `compat/prts/feature/MenuBenchmark.java` | 新增：`/prtsfeatures menubench` 合成菜单基准（64 槽 × 20 万次广播，四种变化模式，交叉 A/B） |
| `mixins.arclight.impl.optimization.json` / `PRTSFeatures.java` | 注册 + tick 汇总 |

---

## 二、性能对照表（测试服实机，2026-08-16 晚）

### 2.1 测试协议
- 环境：prts-test（NeoForge 1.21.1，无 mod，无人），`/prtsfeatures menubench`。
- 合成菜单：`BenchMenu`（64 槽，无 synchronizer / 无 listeners —— 纯 CPU 热路径：每槽 getItem + memoize 分配 + lastSlots diff），200,000 次 `broadcastChanges`/轮。
- 四种变化模式（覆盖真实画像）：**static**（完全静止——玩家开箱不动，主流）；**sparse**（每 100 tick 槽 0 变化一次——玩家偶尔操作）；**denseFirst**（每 tick 槽 0 变化——预检首槽即失败）；**denseLast**（每 tick 槽 63 变化——预检全扫才失败，**最坏情况**）。
- 交叉 A/B：off/on × 2 组，取第 2 组（全预热，消除 JIT 预热顺序效应）。多轮复测取中位。
- 计数：`System.nanoTime` 包围循环；遥测 `[menu-broadcast]` 验证短路/冷却计数。

### 2.2 对照表（64 槽，200,000 次广播，全预热中位）

| 场景 | 预检关（vanilla） | 预检开 | Δ（越快越好） |
|---|---|---|---|
| **static**（静止，主流画像） | ~131ms | ~60ms | **+54%**（省全部 lambda 分配 + 双 getItem/双 matches） |
| **sparse**（每 100 tick 变 1 次） | ~136ms | ~75ms | **+46%**（99% tick 短路 + 变化 tick 走原版） |
| **denseFirst**（每 tick 变首槽） | ~143ms | ~142ms | **持平**（0%，预检首槽失败即交回原版） |
| **denseLast**（每 tick 变末槽，worst） | ~145ms | ~143ms | **持平**（0%，cooldown 把 2× 遍历压回原版成本） |

> 首轮（未预热）曾出现 denseLast -22%，是 JIT 预热顺序效应（首轮 off 腿未充分编译）；交叉 A/B 全预热后多轮复测 dense 场景稳定在 ±3% 噪声内持平。**结论：静止/稀疏场景 +46~54%，密集变化场景零劣化。**

### 2.3 遥测验证（cooldown 生效的机制证据）

| 指标 | 值 | 说明 |
|---|---|---|
| `skippedBroadcasts` | 361,563 | static 20 万 + sparse ~19.8 万全短路 |
| `fullBroadcasts` | 21,261 | ≈ sparse 2000 + dense 40 万/21（每 21 次广播 1 次预检失败）✓ 与 cooldown 设计吻合 |
| `slotsChecked` | 23,140,032 | 短路 tick 扫描的槽位（原版路径省下的检查量） |
| `cooldownHits` | （600 tick 窗口后累计） | 冷却期直接走原版的 tick 数 |

- `fullBroadcasts ≈ dense/21 + sparse` 精确吻合「预检失败 → 20 tick 冷却 → 恢复预检」的周期设计，证明 dense 场景没有每 tick 付 2× 遍历。
- 全程 0 FATAL / 0 异常 / TPS 20.0；menubench 运行期间服务器其他功能不受影响。

---

## 三、模组兼容性（与审计红线逐条核对）

| 兼容机制 | 状态 | 说明 |
|---|---|---|
| 不吞不延事件/广播 | ✅ | 预检全等时原版循环本来无动作（同 diff 判定），无任何广播被跳过；不等时走原版，广播数量/时机与原版逐位一致 |
| **mod 直写容器零漏检** | ✅ | 预检是「全量 diff 的提前等价物」而非脏槽跟踪：`Container.setItem` 绕过 menu 的写入被同一 `lastSlots` 比较捕获（§5.6 文档要求的核心兼容点） |
| 顺序/语义逐位一致 | ✅ | 与原版 `triggerSlotListeners`/`synchronizeCarriedToRemote` 使用完全相同的比较条件（`lastSlots`/`remoteCarried`/dataSlots 值）；`suppressRemoteUpdates` 窗口完全让位 |
| `DataSlot` 语义 | ✅ | 预检只用**只读值快照**（缓存于菜单实例），不触碰原版 `prevValue`/flag；走原版后 RETURN 刷新，与原版收敛到相同值 |
| 无状态残留 | ✅ | 冷却计数与值缓存均为菜单实例字段，随菜单销毁；短路 tick 不刷新缓存（值没变） |
| 配置逃逸阀 | ✅ | `menu-broadcast.enabled` 默认**关**（P3「先实测归因」，生产服确认 `broadcastChanges` 子树占比后再开），`enabled: false` 时注入仅一个布尔判断即返回，零行为变化 |
| 无已知同类优化器冲突 | ✅ | ServerCore/Lithium/Canary/Radium 均无 menu broadcast 优化（已核对），无需让位 |
| 线程安全 | ✅ | `broadcastChanges` 只在主线程（`ServerPlayer.tick`）调用，无并发路径 |

---

## 四、§5.7 / §5.8 实测归因结论（同期完成，不需要代码）

### 4.1 §5.7 Tab list 每 tick 广播 —— **1.21.1 已修复，无优化空间**
反编译核对 1.21.1 源码：
- `ServerPlayer.doTick` **不再调用** `updateTabList`（1.20.1 的 O(玩家²)/tick 结构已移除）；
- `ServerPlayer.refreshTabListName()` 只在显示名实际变化时广播 `UPDATE_DISPLAY_NAME`（`Objects.equals` 短路）；
- `PlayerList.tick` 每 **600 tick（30 秒）** 才广播一次 `UPDATE_LATENCY`。
**结论：文档 §5.7「1.21.1 需实测确认」→ 已确认无每 tick 广播，P3 取消（不实现）。**

### 4.2 §5.8 弹射物 `Level.clip` 逐格查询 —— **收益边际，不立项**
- 1.21.1 形态确认：`AbstractArrow.tick` 每 tick 一次 `Level.clip(new ClipContext(...))` → `BlockGetter.clip` → `traverseBlocks` DDA 逐格（每格 `getBlockState` + `getCollisionShape` + `VoxelShape.clip`）；空气格 `getCollisionShape` 返回 empty shape，`clip` 立即返回 —— **原版已短路空气**，无重复计算可消。
- 测试服实测：summon 800 支箭 + 60s JFR 采样 —— `jdk.ExecutionSample` 252 个样本中 **0 个 clip 相关帧**（低于采样分辨率）；同期 TPS 19.89（常态 19.97，噪声内）。
**结论：箭雨塔场景在空载测试服上 clip 占比低于可测分辨率；无安全可做的优化点（逐格纯几何 + air 已短路），P3 不立项。** 若生产服出现真实弹射物密集场景且 spark 归因显示 `Level.clip` 子树显著，再按「高密度场景节流/预算」立项。

---

## 五、遗留与后续

- **菜单广播**：`menu-broadcast.enabled` 保持默认关；生产服（13 人）用 spark 看 `broadcastChanges` 子树占比 → 若显著再开（预期静止菜单场景 +46~54%）。开启后观察 `[menu-broadcast]` 遥测的 `skippedBroadcasts`/`fullBroadcasts` 比例确认短路率。
- **可选调参**：`COOLDOWN_TICKS = 20` 硬编码；若生产服出现「每 tick 变化的 mod 菜单」（AE2 终端类），cooldown 已保证不劣化，无需调整。
- **未做**：脏槽跟踪（被预检方案取代——预检语义更强且零漏检）、降频（语义有风险，文档 §5.6 已列，不做）。
