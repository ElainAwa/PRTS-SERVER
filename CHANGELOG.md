# Changelog

本 fork（`ElainAwa/PRTS-SERVER`，分支 `1.20.1`）相对上游 Luminara 的定制变更记录。
遵循「零感知底层优化」原则：只让同样的工作更省资源，或修复崩溃，绝不改变玩法。
版号定义在 [`build.gradle`](build.gradle) 的 `version`；完整方案文档见仓库 `docs/`。

---

## [1.0.59] — 富版激活范围 + mobcap 移植 + 碰撞禁用 + NBT 崩溃防御

- **P2-1 富版激活范围反移植**：ServerCore 激活范围（activation-range）完整落地 1.20.1（17 mixin + 5 支持类 + 2 桥接类），静态版移出 mixin 注册由富版接管，默认开启
- **mob_spawning 移植**：mob-spawning 段（enforce-mobcap 默认 false），支持类 EnforcedMobcap/MobSpawnConfig 等 + 4 mixin，服务端强制繁殖上限可选
- **P2-3 实体碰撞禁用**：`disable-entity-collisions` 开关（默认关），实体碰撞判定前置短路
- **P1-5 NPI verify 默认 false**：NearbyPlayerIndexSpec 验证降噪
- **StringTagVisitorMixin NBT 崩溃防御**：`CompoundTag.toString()` 排序遇 null key 崩溃（carryon 扛方块实测触发）防御，纯过滤零兼容风险
- 清理 fork 上游剩余无用 .github 文件

## [1.0.58] — ClientModGuard 重构与误判修复

- ClientModGuard 单文件拆分为 6 类（GuardPaths / GuardMarkers / ModJarParser / GuardConfig / CrashSelfHeal），行为不变，后续维护定位更快
- 修复 create_hypertube 误隔离：该模组客户端逻辑有运行时 dist 保护，静态判毒属误判，已加入内置安全集豁免

## [1.0.56] — 代码审查 P1/P2 缺陷修复

对 1.20.1 全树定制代码审查后的问题修复（报告 `docs/code-review-1201-2026-08-02.md`，未入库）：

- 区块广播短路守卫（注入点失效时恒回退原版广播，杜绝客户端全黑）
- routeA×routeB 实体广播互斥（routeB 启用时 routeA 回退原版）
- 删除语义错位的 `ServerLevelMixin_Optimize`（原实改 randomTickSpeed；prts.yml 键标注废弃）
- ItemEntity 双 mixin 同名字段 `lastTick` 加前缀隔离
- `EntityActuallyHurtPrintStackMixin` ThreadLocal 悬挂超时自愈
- VillagerBrainOffloader 静态单例；删死代码 `ChunkManager.disableSpawnChunks`；ticketpropagator `require` 加固

## [1.0.55] — ServerCore 完整移植（Phase A–F）

将 [ServerCore](https://github.com/Wesley1808/ServerCore) 优化集完整移植到 1.20.1 Forge 服务端，统一入口 `config/servercore.yml`：

- **Phase A** 中央配置 + breeding-cap 繁殖上限
- **Phase B** dynamic 动态视距/模拟距/区块刻距调节（默认关闭）
- **Phase C** features：物品/经验球合并、防走进未加载区块、自动保存间隔、spawn-chunks 开关
- **Phase D** commands：`/servercore status|reload|settings`、`/mobcaps`
- **Phase E** optimizations：地图 tick、区块广播增量、雷击/冰霜随机刻优化
- **Phase F** `/statistics`：TPS/MSPT/区块/实体/方块实体统计（byType/byPlayer 分页）

实证放弃项：`optimizations.players`（与 Arclight `@Overwrite PlayerList.respawn` 冲突）、`ticking.chunk.cache`（依赖 mixin-extras/LVT）、`features.ticking`（树内 minecrafttweaks 已同语义）。

## [1.0.54] — ClientModGuard v22：autoQuarantine 升级为真·总开关

`false`（默认）整套客户端自检完全关闭；`true` 完整启用预检 + 崩溃隔离 + 自愈。

## 早期（1.0.15 ~ 1.0.31）

routeB 空间化实体追踪、ticketpropagator、ServerCore 12 项、move-zero-velocity、async-logging、
核心原生调优（chunk-load-rate-limit/并行世界初始化/异步保存）、NPI 空间索引、ChampionsFix、
黑色区块修复（chunkwatching 回退 vanilla）、RevelationFix null 守卫等。细节见旧版本文档。
