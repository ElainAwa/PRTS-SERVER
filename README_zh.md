# PRTS-stable-Trials — Minecraft 1.20.1 / Forge

**[English](./README.md) · [中文文档](./README_zh.md)**

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/CraftAmethyst/Luminara) → **本 fork：PRTS**（[ElainAwa](https://github.com/ElainAwa) 维护，私有生产加固）

## 定位

面向重度模组化、多人生产服务器构建：**Forge 模组与 Bukkit/Spigot 插件同服运行**。
仅做零感知的底层优化与崩溃修复——玩法不变，只降低相同逻辑的资源开销。

## 功能与溯源

> 原模组与 Luminara（Arclight）基座不兼容。为避免安装它们带来的冲突与事故，其代码已并入核心（去模组化改写）。并入主要为省事——要让核心与两个模组并存兼容需要改动太多，不值得。

| 功能 | 来源 | 仓库 |
|---|---|---|
| ServerCore 全量移植（breeding-cap / dynamic / features / commands / optimizations + /statistics） | ServerCore mod | https://github.com/Wesley1808/ServerCore |
| routeB 空间实体追踪 | VMP mod（AreaMap 算法） | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator（延迟 8 向区块票传播） | VMP（Paper 派生算法） | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / 异步日志 | VMP mod | https://github.com/RelativityMC/Very-Many-Players |
| 村民大脑卸载（minecrafttweaks） | Mohist server OptVillager | https://github.com/MohistMC/Mohist |
| NearbyPlayerIndex（NPI）空间玩家索引 | 自研（基于 Paper AreaMap） | https://github.com/PaperMC/Paper |
| ClientModGuard 客户端模组预检 / 崩溃自愈 | 自研 | — |
| 崩溃修复（ChampionsFix / RevelationFix 等） | 自研 | — |

## 构建

- 环境要求：JDK 21（构建）+ 固定 Gradle 8.14.5（发行版 jar，非 `./gradlew`）
- 命令：`gradle --no-daemon :arclight-common:createSrgToMcp --rerun-tasks` → `gradle --no-daemon :collect --offline`
- 产物：`build/libs/PRTS-1.20.1-<version>.jar`
- 规则：切勿运行 `:arclight-forge:clean`（会破坏 reobf SRG 缓存并损坏 refmap）；仅对仓库同步的最终构建升版本号

## 部署

1. 复制新 jar 到服务端根目录，指向你的启动脚本
2. 启动——内嵌 `common.jar` 内容变化时自动重解包，同名版本重建也会刷新；无需手动清理 `.arclight`

## 配置

- `prts.yml`（服务端根目录）：遗留优化（NPI、routeB、tracking 等）
- `config/servercore.yml`：ServerCore 五段（breeding-cap / dynamic / features / commands / optimizations）；缺失段自动补全，启动时生效

## 兼容性

- Forge 模组 + Bukkit/Spigot 插件同服
- 已验证共存的优化模组：ModernFix、FerriteCore、Canary、Saturn、KryptonReforged、Noisium、Radium、MemoryLeakFix、PacketFixer、Spark 等

## 许可

[GPL v3](LICENSE) 开源，与上游一致。
