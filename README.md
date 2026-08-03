# PRTS-stable-Trials — Minecraft 1.20.1 / Forge

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源；源码与移植细节见仓库 `docs/` 目录。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/QianMo0721/Luminara) → [PRTS](https://github.com/QianMo0721/PRTS) → **本 fork**（[ElainAwa](https://github.com/ElainAwa) 维护，私有生产加固）

## 定位

多模组、多人重载生产环境专用：**Forge 模组与 Bukkit/Spigot 插件同服运行**。
只做零感知的底层优化与崩溃修复 —— 不改玩法，仅降低同类逻辑的资源开销。

## 功能与来源

> 原模组无法兼容 Luminara (arclight) 基座，为避免装模组引发的冲突与事故，已将对应代码并入核心（去模组化重写），并入核心主要是图省事，修改核心来兼容这两个模组同时运行需要动很多东西，不太划算。

| 功能 | 来源 | 仓库 |
|---|---|---|
| ServerCore 完整移植（breeding-cap / dynamic / features / commands / optimizations + /statistics） | 移植自 ServerCore 模组 | https://github.com/Wesley1808/ServerCore |
| routeB 空间化实体追踪 | 移植自 VMP 模组（AreaMap 算法） | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator（延迟 8 向区块 ticket 传播） | 移植自 VMP（源自 Paper 算法） | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / async-logging | 移植自 VMP 模组 | https://github.com/RelativityMC/Very-Many-Players |
| 村民脑切（minecrafttweaks） | 移植自 Mohist 服务端 OptVillager | https://github.com/MohistMC/Mohist |
| NearbyPlayerIndex（NPI）最近玩家空间索引 | 本 fork 自研（基于 Paper AreaMap 数据结构） | https://github.com/PaperMC/Paper |
| ClientModGuard 客户端模组预检 / 崩溃自愈 | 本 fork 自研 | — |
| 崩溃修复（ChampionsFix / RevelationFix 等） | 本 fork | — |

## 版本

- 当前：**v1.0.56**（GitHub Releases 获取；Latest 跟随最新发布）
- 演进记录：[CHANGELOG.md](CHANGELOG.md)

## 构建

- 环境：JDK 21（构建）+ 锁定的 Gradle 8.14.5（分发 jar，非 `./gradlew`）
- 命令：`gradle --no-daemon :arclight-common:createSrgToMcp --rerun-tasks` → `gradle --no-daemon :collect --offline`
- 产物：`build/libs/PRTS-1.20.1-<版号>.jar`
- 约束：勿执行 `:arclight-forge:clean`（会删 reobf SRG 缓存导致 refmap 损坏）；版号仅在同步仓库的终版更新

## 部署

1. 复制新 jar 到服务端根目录，启动脚本指向它
2. **强制重解包**：外层 jar 是启动器，实际类在内部 `common.jar` —— 必须清空 `.arclight/mod_file/*` 与 `.arclight/class_cache/*`，否则复用旧 common 不生效
3. 启动：`java -jar PRTS-1.20.1-<版号>.jar nogui`

## 配置

- `prts.yml`（服务端根目录）：既有优化（NPI、routeB、tracking 等）开关
- `config/servercore.yml`：ServerCore 五段开关（breeding-cap / dynamic / features / commands / optimizations），缺失段启动时自动补写并生效

## 兼容性

- Forge 模组 + Bukkit/Spigot 插件同服运行
- 已验证可共存优化模组：ModernFix、FerriteCore、Canary、Saturn、KryptonReforged、Noisium、Radium、MemoryLeakFix、PacketFixer、Spark 等

## 许可

基于 [GPL v3](LICENSE) 开源，与上游一致。
