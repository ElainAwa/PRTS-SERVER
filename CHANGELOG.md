# Changelog

本 fork（`ElainAwa/Luminara-stable-Trials`）相对上游 Luminara 的定制变更记录。
遵循「零感知底层优化」原则：只让同样的工作更省资源，或修复崩溃，绝不改变玩法。

版号定义在 [`build.gradle`](build.gradle) 的 `version`。

---

## [1.0.31] — NearbyPlayerIndex（最近玩家空间索引）

**新增优化**：空间索引加速"最近玩家 / 附近存活玩家"查询，接管两处原版全玩家线性扫描热点：

- `Mob.checkDespawn` 内 `Level.getNearestPlayer(Entity, D)` 的无界扫描（每生物每 tick 调用）。
- `BaseSpawner.isNearPlayer` 内 `Level.hasNearbyAlivePlayer(DDDD)`（刷怪笼 16 格判定）。

**实现要点**：

- 查询侧用 `@Redirect`（`require=1`），属主经 `javap` 字节码钉死为 `Level`——冲突会硬崩而非假通过。
- 索引挂在 `ChunkMap` 层（`@Unique` + TAIL 注入旁路同步），**不碰 `PlayerMap`、不碰发包路径**。
- 桶半径 R=10 chunk，144 格数学守卫（桶外玩家水平距离必 ≥161 格，采纳条件 ≤144 保证全局最优）。
- **三重安全**：原版记账永远权威 / verify 双跑（默认开）/ 异常自毒 + owner 线程守卫。
- 默认 `enabled=false` 纯待命，行为与原版 100% 一致；配置见 `luminara.yml` 的 `nearby-player-index`。

## [1.0.30] — 黑色区块修复（chunkwatching 回退）

**修复**：玩家原地转圈时远处区块黑色不加载、退出重进正常但频繁复现。

- **根因**：Forge 1.20.1 的 `PlayerMap` 是 `player→flag` 单值映射，`getPlayers(long)` 忽略参数返回**所有玩家**，
  由 `PlayerChunkMap` 按 `viewDistance` 逐玩家距离过滤发包。此前 chunkwatching 的 `@Overwrite getPlayers`
  返回 `AreaMap` 预过滤子集，与该契约冲突 → 玩家集合不完整 → 远端区块漏发。移植基础建立在错误的
  `chunkPos→Set` 索引假设上。
- **修复**：移除 `@Overwrite getPlayers`，停用 `AreaMap` 空间索引，发包回归 100% vanilla。
  chunkwatching mixin 仍加载（仅留 active 日志，不再改变任何行为）。

## [1.0.29] — 崩溃 A 根治（RevelationFix inWhitelist NPE）

- 新增 `@Pseudo` mixin `CommonConfigWhitelistMixin`，对 RevelationFix `inWhitelist(Entity)` / `inWhitelist(Item)`
  两个重载在 HEAD 注入 null 守卫：字段为 null 时打 `WARN` 并返回 `false`，避免 NPE。
- RevelationFix 4.0 经 jarjar 内嵌于 `GoetyRevelation-2.3.1.jar`，运行时仍加载，故需共存修复而非移除。

## [1.0.28] — 崩溃 B 缓解（看门狗超时）

- 纯旋转数据包跳过 `move()`，削减 `updatePosition` 主线程冗余负载，降低看门狗 60s 超时概率（缓解非根治）。

---

## 基线（1.0.15 ~ 1.0.27）

早期版本已落地并生产可用的优化（此后持续维护）：

- routeB 空间化实体追踪、ticketpropagator（Paper 式区块 ticket 传播）、ServerCore 12 项。
- move-zero-velocity、async-logging。
- 核心原生：chunk-load-rate-limit、并行世界初始化 + 异步数据加载、异步世界保存。
- ChampionsFix：ChampionsConfig 惰性初始化（`bakeCommon()` + `bake()`，避免反射私有 final 字段）。
