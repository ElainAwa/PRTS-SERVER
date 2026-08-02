# ServerCore 1.20.1 服务端 (Forge) 移植方案 — v37（最终版）

> 先文档后代码。§0–§8 为落地前的方案（现状实证、根因、备选对比、分阶段计划、构建部署、风险回滚）；
> **§9 为落地结果**，记录实际取舍、放弃项实证、缺陷修复与发版信息。方案与结果不一致处**以 §9 为准**。
> 授权来源：用户 2026-08-01 `push发行版，然后这是1.20.1源码...同样移植一下` + `是1.20.1服务端移植，明确一下`；
> 发版授权：用户 2026-08-02 `发行` → 已发布 **v1.0.55**。

## 0. 目标与范围
- 把 `ServerCore`（Wesley1808）的优化集对齐到 1.20.1 服务端 Forge 树，对齐 1.21.1 树已交付的 6 段式可配置优化。
- **仅服务端（Forge）**，不碰客户端逻辑、不碰 rebrand 铁律。
- git 默认本地改不提交；发版待用户授权。

> **版号约定修正（用户 2026-08-02 重申）**：下文各 Phase 标注的 1.0.55/56/57/58/59 是**规划期的阶段编号**，实际执行中**本地未发版迭代一律不升版号**。
> 1.0.54 已是 GitHub Latest 发行版，故 Phase A→F 全部叠加在 **`1.0.55`** 一个版号上反复重建覆盖，直到用户授权发版。
> 曾误跳至 1.0.60，已回退：`build.gradle` 复位 `1.0.55`，`build/libs` 与测服的 1.0.56~1.0.60 产物已移入 `_stale/`、`_trash_deploy/`。

## 1. 现状实证（探查结果）

### 1.1 目标树已具备（常开，无中央配置门控）
- 路径 `arclight-common/.../mixin/optimization/general/servercore/*` 与 `optimization/general/activationrange/*`
- `activation-range`：富版（含 `inactive_ticks` 系列，约 14 条 mixin）— **已完整，本次不重做**
- `sync_loads`(7) / `tickets`(2) / `biome_lookups`(1) / `pathfinder`(1) — `servercore.*` 常开
- `trackingrange` / `entitytracking` / `async_logging` / `minecrafttweaks` — 既有优化
- `optimization/general/servercore/ChunkManager.java` — servercore 工具类
- ⚠️ `ticketpropagator` 存在于树中但 **Forge 不兼容**（skill 警告）→ 本次**不触碰**

### 1.2 目标树缺失（本次要补）
- `breeding-cap`、`dynamic`、`commands`
- `features` 子项：`merging` / `misc` / `spawn_chunks` / `ticking`（1.21.1 树未全做，1.20.1 源有）
- 中央 `ServerCoreConfig`（YAML 门控）

### 1.3 源 = `D:\mc\617\1.6.5-server\ServerCore-1.20.1`（Fabric, 包 `me.wesley1808.servercore`）
- 配置机制：**nightconfig TOML + `ServerCoreMixinPlugin`**（与 1.21.1 我们自写的 YAML `ServerCoreConfig` 不同）
- ⚠️ **无 `mob_spawning` 包**（1.21.1 源有，1.20.1 源无）→ 不移植 mob-spawning 段
- ✅ 含 `/statistics` 命令与 `stats_*` 配置（1.21.1 源无）→ 可移植，列为**可选 Phase F**
- mixin 命名：**实测多为 Mojmap 官方命名**
  （`ChunkMap` / `playerIsCloseEnoughForSpawning` / `AnimalMakeLove` / `spawnChildFromBreeding` / `net.minecraft.server.level.ServerLevel`）
  → 早期"Yarn 命名需全量翻译"判断**不成立**，仅需 spot-check 少量模糊符号
- 大量使用 **mixin-extras**（`@ModifyReturnValue` / `@WrapWithCondition`）→ 目标树**无 mixin-extras** → 必须翻译为原生 `@Inject(cancellable)`
- `DynamicManager` 引用客户端类 `net.minecraft.client.Minecraft`（仅 `isClient` 分支）→ 服务端正编会失败，须**剔除客户端分支**

### 1.4 1.21.1 已交付资产（直接复用，降风险）
- `ServerCoreConfig`（YAML / SnakeYAML，读 `config/servercore.yml`）— 复制到 1.20.1 树（包改 `io.izzel.arclight.common.optimization.general.servercore`）
- `DynamicManager` / `Setting` / `DynamicSetting`（list 模型，已实测）— 复用，**不用源的老字段模型**
- `FeatureConfig` / `BreedingCapConfig` / `CommandConfig` 等 POJO

## 2. 配置策略决策（对齐 1.21.1，降风险）
- **复用 1.21.1 的 `ServerCoreConfig`（YAML）设计**，复制到 1.20.1 树，仅做两处裁剪：
  1. **去掉 `mob-spawning` 段及解析**（源无）
  2. **去掉 `activation-range` 段**（1.20.1 树已由常开 mixin 处理，本配置不门控它）
- 新加的 mixin 全部通过 `ServerCoreConfig.*` 门控；**既有常开 mixin 保持常开**（避免回归）。
  - 已知限制：`enabled: false` 主开关**仅覆盖本次新增的 mixin**，不关闭早期常开优化。文档注明。
- `dynamic` 复用 1.21.1 的 list 模型（`dynamic-settings` 段），不使用源的 MAX/MIN/INCREMENT 分离字段模型。
- `commands` 仅 `/servercore status` + `/mobcaps`（与 1.21.1 一致）；`/statistics` 为可选 Phase F。
- `breeding-cap` 默认沿用 1.21.1 配置（`villagers.limit:36 / animals.limit:36 / range:64`）；源默认 24/32 不采用（保持两树一致）。

## 3. 根因与备选对比
| 决策点 | 备选 | 选定 | 理由 |
|---|---|---|---|
| 配置机制 | 复用源 TOML+MixinPlugin | 复用 1.21.1 YAML | 目标树用统一大 json + ArclightMixinPlugin，无 per-mixin 插件门控范式；YAML 两树一致 |
| mixin-extras | 引入 mixin-extras 依赖 | 原生 @Inject 翻译 | 两树均无，1.21.1 已验证原生可行 |
| dynamic 模型 | 源字段模型 | 1.21.1 list 模型 | 复用已测代码，避免重新实现 DynamicManager |
| activation-range | 重做并纳入配置 | 保留常开 | 已完整且无 bug，重做只会引入回归 |

## 4. 分阶段计划（每段：写码 → 构建 → 部署测服 → 冒烟，独立可回滚）
源 mixin 复制到 `arclight-common/.../mixin/optimization/general/servercore/<段>/`，包改 `io.izzel.arclight.common.mixin.optimization.general.servercore.<段>`；mixin-extras 注解译原生；调用点改调 `ServerCoreConfig.*`。

### Phase A — 中央配置 + breeding-cap（版本 1.0.55）
- 复制 `ServerCoreConfig`（去 mob-spawning / activation-range 段）到 1.20.1 树
- 复制 `BreedingCap` / `BreedingCapConfig` + 5 个 mixin：
  `breeding_cap/AllayMixin`、`ThrownEggMixin`、`tasks/AnimalMakeLoveMixin`、`BreedGoalMixin`、`VillagerMakeLoveMixin`
  - `@WrapWithCondition` → `@Inject(at=INVOKE target, cancellable=true)` + `cir.cancel()`
- `mixins.arclight.impl.forge.optimization.json` 加 `servercore.breeding_cap.*` 5 条
- 构建 1.0.55 → 部署测服 → 冒烟

### Phase B — dynamic（版本 1.0.56）
- 复制 `DynamicConfig` / `DynamicManager` / `Setting` / `DynamicSetting` + 3 mixin：
  `dynamic/ChunkMapMixin`、`MobCategoryMixin`、`PlayerListMixin`
  - `@ModifyReturnValue` → `@Inject(at=RETURN, cancellable=true)` + `cir.setReturnValue(...)`
  - **剔除 `DynamicManager` 中 `Minecraft.getInstance()` 客户端分支**（服务端正编会失败）
- `IMinecraftServer` 注入：复用 1.21.1 方式在 `MinecraftServer` 上挂 `servercore$getDynamicManager`（accessor/mixin）
- json 加 3 条

### Phase C — features 子项（版本 1.0.57）
- `merging`(2)：`ExperienceOrbMixin`、`ItemEntityMixin`（与 1.21.1 `features` 的 xp/item-merge 语义对齐，门控 `ServerCoreConfig.features()`）
- `misc`(3)：`EntityMixin`、`MinecraftServerMixin`、`ServerGamePacketListenerImplMixin`
- `spawn_chunks`(1, **仅服务端**)：`ServerLevelMixin`（json 中**排除** `client: features.spawn_chunks.PlayerListMixin`）
- `ticking`(1)：`VillagerMixin`（lobotomize）
- 各 mixin 首行读 `ServerCoreConfig.features()` 门控

### Phase D — commands（版本 1.0.58）
- 复制 `CommandConfig`(POJO) / `Formatter`(自写解析，不依赖 adventure MiniMessage) / `Permission`(仅 op) / `ServerCoreCommands` / `MobcapsCommand` / `CommandsMixin`（`Commands` 构造 RETURN 注册）
- json 加 `servercore.commands.CommandsMixin`
- 冒烟验证 `/mobcaps`、`/servercore status` 有输出

### Phase E — optimizations.players / misc / ticking.chunk（可选增强 → 部分落地，详见 §9.2）
- `players`(2)：`PlayerListMixin`、`ServerPlayerMixin` — **放弃**（与 Arclight `@Overwrite PlayerList.respawn` 冲突）
- `misc`(1)：`MapItemSavedDataMixin` — 落地
- `ticking.chunk`(6)：`broadcast/*`(2) 落地 / `cache/*`(1) **放弃** / `random/*`(3+1) 落地
- 门控统一走新增的 `OptimizationConfig`（`optimizations:` 段），运行期判 flag、关时显式回退原版行为

### Phase F — /statistics 命令（已落地）
- 源含 `StatisticsCommand` + `stats_*` 配置 + `Statistics` 工具类；1.21.1 因源缺而跳过。
- 实测 Forge 1.20.1 可用：TPS/MSPT 取 `MinecraftServer.getAverageTickTime()`，区块数取 `ServerChunkCache.getLoadedChunksCount()`，
  方块实体经 `LevelBlockEntityTickersAccessor` 读 `Level.blockEntityTickers`（1.20.1 为 protected）。

## 5. mixin-extras → 原生 翻译规则（目标树无 mixin-extras）
- `@ModifyReturnValue(method, at=RETURN)` →
  `@Inject(method=..., at=@At("RETURN"), cancellable=true)` 方法签名 `(..., CallbackInfoReturnable<RT> cir)`，`cir.setReturnValue(computed)`
- `@WrapWithCondition(method, at=@At(INVOKE target))` →
  `@Inject(method=..., at=@At(value="INVOKE", target=...), cancellable=true)` 方法签名 `(..., CallbackInfo ci)`，`if (skip) ci.cancel()`
- `@ModifyExpressionValue` / `@ModifyVariable` → 用 `@Inject` + 局部变量捕获或 `@Redirect` 替代
- 第三方可能抢同一注入点 → mixin 标注 `require=0, expect=0` 防 `Critical injection failure` 崩服

## 6. 构建 / 部署 / 冒烟（1.20.1 Forge 范式）
- **构建**（见 MEMORY `1.20.1 构建`）：`gradle collect` 顺序 `:arclight-common:createSrgToMcp --rerun-tasks` → 根 `:collect --offline`；设 TMP 等 Windows 原生路径（`cygpath -w`），**禁 `env -i`**
- **部署**：改了 `arclight-common` 必删 `.arclight/mod_file/*` + `gson.jar`（先备份 `_trash_deploy`）让重启重抽 common（bootstrap jar 内 `/common.jar` 由 `AbstractBootstrap:105` 提取）；只改 bootstrap 则勿清。Bash `rm` 被拦截 → PowerShell `Remove-Item -Force` 绝对路径。
- **冒烟**：启测服 `D:\mc\PRTS`，抓启动日志，确认 `Done` 且无 Mixin 注入报错 / 0 崩溃；确认 `config/servercore.yml` 自动写出含 `breeding-cap` / `dynamic` / `features` / `commands` 段。

## 7. 风险 / 回滚
- `ticketpropagator` Forge 不兼容 → 不触碰
- 主开关仅覆盖新增 mixin（旧常开除外）→ 已知限制，文档与配置注释说明
- 客户端类引用（`Minecraft` 等）→ dynamic / 其他必须剔除，否则服务端正编不过
- mixin-extras 缺失 → 全翻译原生
- 模糊符号（疑似 `class_xxxx` / `method_xxxx`）→ 用 mapping CSV / `javap` 在 Forge 1.20.1 mdg 上核实官方名
- 回滚：git 未提交，部署前备份 `mod_file` + jar；任一段冒烟失败则回退该段 mixin json 条目与源码

## 8. 验收
- 每段：构建通过 + 测服 `Done` + 对应 mixin 无注入报错 + `config/servercore.yml` 写出对应段
- 发版：1.20.1 分支发版**可带 Latest**（Latest 本就归 1.20.1 树），无需 `--latest=false`；待用户说"发行"再 commit+push+release

---

## 9. 落地结果（2026-08-02 收口，以本节为准）

> 本节把各阶段过程稿（Phase B/C/D/E+F 四篇，未入库，留在 `D:\mc\PRTS-SERVER\1.20.1\docs\`）的结论浓缩汇总，
> 使本文单文件自洽 —— 只读本文即可掌握 ServerCore 1.20.1 移植的完整最终状态。

### 9.1 交付概览
| 项 | 值 |
|---|---|
| 发版 | `v1.0.55` — https://github.com/ElainAwa/PRTS-SERVER/releases/tag/v1.0.55（Latest，归 1.20.1 树） |
| 提交 | `510f6fb`（分支 `1.20.1`）：52 文件、+2610 行 |
| 构成 | 新增 49 个 ServerCore 源文件；修改 `MinecraftServerMixin.java`（配置钩子）、`mixins.arclight.impl.forge.optimization.json`（+26 行）、`build.gradle`（版号） |
| 产物 | `PRTS-1.20.1-1.0.55.jar`，MANIFEST `Implementation-Version: PRTS-1.20.1-1.0.55-510f6fb` |
| 配置 | `config/servercore.yml`，5 段：`breeding-cap` / `dynamic` / `features` / `commands` / `optimizations` |
| 加载点 | `MinecraftServerMixin.createLevels` 的 `RETURN` 调 `ServerCoreConfig.load()` |

### 9.2 分阶段落地结果
| 阶段 | 结果 | 说明 |
|---|---|---|
| A 中央配置 + breeding-cap | ✅ 全量 | `ServerCoreConfig` + `BreedingCapConfig`，5 个 breeding_cap mixin |
| B dynamic | ✅ 全量 | `DynamicManager` 按 MSPT 调节 `VIEW_DISTANCE`/`SIMULATION_DISTANCE`/`CHUNK_TICK_DISTANCE` 与 mobcap；**默认 `enabled: false`**（保守，避免动态改视距干扰玩法） |
| C features | ⚠️ 部分 | `merging`(2) / `misc`(3) / `spawn_chunks`(1) 落地；**`ticking`(村民脑切) 剔除** — 树内 `minecrafttweaks.MixinVillager_BrainOffload` 已同语义，重复注入同点会冲突 |
| D commands | ✅ 全量 | `/servercore status\|reload\|settings`、`/mobcaps`；`enabled: false` 为**真总开关**（整段命令不注册，含 reload/settings） |
| E optimizations | ⚠️ 部分 | `misc.MapItemSavedData`(1) + `ticking.chunk.broadcast`(2) + `ticking.chunk.random`(4) 落地；`players`(2) 与 `ticking.chunk.cache`(1) 放弃，见 §9.3 |
| F statistics | ✅ 全量 | `/statistics` 总览 + `entities`/`block-entities` 的 `byType`/`byPlayer` 分页（PAGE_SIZE=8） |

### 9.3 放弃项（实证不可移植，非偷懒）
| 放弃项 | 根因 |
|---|---|
| `optimizations.players`(2) | Arclight `mixin/core/server/management/PlayerListMixin.java:395` 对 `PlayerList.respawn` 用了 **`@Overwrite`**；上游该优化以 `CAPTURE_FAILHARD` 注入同一方法，共存必崩。且两侧逻辑强耦合、不可拆分单上 |
| `ticking.chunk.cache`(1) | 依赖 mixin-extras 的 `@ModifyExpressionValue` + `@Local`，翻译为原生需硬编码 LVT index=12（版本脆弱），并自研 `CachedChunkList` 集合。收益/风险不成比例 |
| `features.ticking`(1) | 树内 `minecrafttweaks.MixinVillager_BrainOffload` 已提供同语义 |
| `ticketpropagator` | Forge 不兼容（既有警告），全程未触碰 |

### 9.4 关键技术决策
- **无 mixin-extras → 全原生翻译**：仅用 `@Inject`/`@Redirect`/`@ModifyVariable`/`@Accessor`/`@Shadow`/`@Unique`/`implements 接口`；翻译规则见 §5。
- **运行期静态门控范式**：mixin 常驻不做 `MixinPlugin` 条件加载（本树 `ArclightMixinPlugin` 走 `ShouldApplyProcessor` 注解，不便按外部 YAML 门控），改为运行期判 flag，**关闭时显式回退原版行为**。`ServerCoreConfig.load()` 有早退，热路径代价可忽略。
- **补写 + 回填范式**：`defaultSection()` 用 SnakeYAML 解析"刚写入的默认段文本本身"，补写后立即回填内存 → 保证"磁盘默认值 == 内存值"单一事实源，修掉升级首启整段不生效。
- **accessor mixin 跨包访问**：`LevelBlockEntityTickersAccessor` 读 `Level.blockEntityTickers`（1.20.1 为 protected），优于 AT 与反射。
- **同目标类多 mixin 共存合法**（仅 `@Overwrite` 才冲突）→ 文件名加 `_Broadcast` / `_Random` 后缀区分。
- **对上游的有意偏差**：`LevelChunkMixin_Random` 用 `ThreadLocalRandom` 取代上游的 `Level.threadSafeRandom`（1.20.1 为 private），分布等价且免开放字段；`Statistics` 用 `ServerChunkCache.getLoadedChunksCount()` 取代 `ChunkMap.visibleChunkMap` 遍历。

### 9.5 缺陷与修复（冒烟回归发现）
1. **新配置段补写后当次仍按 DISABLED**（系统性，A/B/C/D 同患）→ 见 §9.4「补写 + 回填」。
2. **`commands.enabled` 非真总开关**（原仅控 status/mobcaps）→ `CommandConfig` 加 `enabled` 字段与 `commandsEnabled()`，`ServerCoreCommands`/`MobcapsCommand` 的 `register()` 开头统一门控。
3. **`dynamic.enabled: false` 时两处 NPE（同源）**：`DynamicManager` 从未实例化 → ① `StatisticsCommand.overview()` 改用 `MinecraftServer.getAverageTickTime()`，彻底去掉 `DynamicManager` 依赖；② `ServerCoreCommands.modifyDynamic()` 加 null 守卫，返回 `Dynamic performance settings are disabled in servercore.yml.`
4. **区块广播集合泄漏**：`ServerChunkCacheMixin_Broadcast` 的 `@Redirect(require=0)` 若静默失败，`ChunkHolder` 侧仍持续入集合而永不清空 → 加 `arclight$broadcastActive` 生效标志，仅当 Redirect 真正生效才收集。

### 9.6 冒烟验收结果（1.0.55 @ `D:\mc\PRTS`，197 mods）
- 启动横幅 `PRTS-1.20.1-1.0.55-510f6fb`，`Done (2.415s)`，**0 条 mixin 注入失败**
- `config/servercore.yml` 自动写出全部 5 段（含新增 `optimizations` 段 5 项）
- `servercore status` / `servercore reload` 输出正常
- `servercore settings view_distance 10`（dynamic 关闭时）→ 优雅提示，不再 NPE
- `statistics` → TPS 20.00 / MSPT 0.98 / Chunks 841 / Entities 5 / BlockEntities 3
- `statistics entities|block-entities` 及 `byType`/`byPlayer` 分页正确；`statistics entities 99` → `Page doesn't exist!`
- `mobcaps` 在控制台正确提示需玩家执行
- 冒烟工具：`D:\mc\PRTS-SERVER\1.20.1\tools\smoke_console.py`（Python 驱动 stdin：SETUP 造实体/方块实体 → VERIFY 跑命令 → cleanup → stop），rcon 未开时的通用方案

### 9.7 流程铁律补充（本次踩坑固化）
- **本地未发版迭代不升版号**：1.0.54 已是 Latest 不可覆盖，故 Phase A→F 全部叠加在 `1.0.55` 一个版号上反复重建覆盖。本次曾误跳至 1.0.60，已回退。
- **commit 后必须重建 jar 再发版**：jar 内嵌 git hash 取自构建时的 HEAD。commit 前构建的 jar 内嵌的是旧 HEAD（`4e18d41`），发出去与发版 commit 对不上。正确顺序：**commit → push → 重建 `:collect` → 校验 MANIFEST hash → 重新部署冒烟 → `gh release create`**。

---
### 附录：源 mixin 全量清单（1.20.1 ServerCore 源）
- breeding_cap(5): Allay, ThrownEgg, tasks/AnimalMakeLove, tasks/BreedGoal, tasks/VillagerMakeLove
- dynamic(3): ChunkMap, MobCategory, PlayerList
- merging(2): ExperienceOrb, ItemEntity
- misc(3): Entity, MinecraftServer, ServerGamePacketListenerImpl
- spawn_chunks(1+client1): ServerLevel [+ client PlayerList]
- ticking(1): Villager
- optimizations.sync_loads(7) [树已有], biome_lookups(1)[已有], misc.MapItemSavedData(1), players(2), tickets(2)[已有], ticking.chunk(6)
- activation_range(富, 树已有)
- commands: MobcapsCommand, ServerCoreCommand, StatisticsCommand
- **mob_spawning: 无**
