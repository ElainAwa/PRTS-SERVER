# PRTS-FeudalKings — Minecraft 1.21.1 / NeoForge

**[English](./README.md) · [中文文档](./README_zh.md)**

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/CraftAmethyst/Luminara) → **本 fork：PRTS**（[ElainAwa](https://github.com/ElainAwa) 维护，私有生产加固）

## 定位

面向重度模组化、多人生产服务器构建：**NeoForge 模组与 Bukkit/Spigot 插件同服运行**。
仅做零感知的底层优化与崩溃修复——玩法不变，只降低相同逻辑的资源开销。

## 功能与溯源

> 原模组与 Luminara（Arclight）基座不兼容。为避免安装它们带来的冲突与事故，其代码已并入核心（去模组化改写）。并入主要为省事——要让核心与两个模组并存兼容需要改动太多，不值得。

| 功能 | 来源 | 仓库 |
|---|---|---|
| ServerCore 全量移植，六段（activation-range / breeding-cap / mob-spawning / features / dynamic / commands） | ServerCore mod | https://github.com/Wesley1808/ServerCore |
| routeB 空间实体追踪 | VMP mod（AreaMap 算法） | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator（延迟 8 向区块票传播） | VMP（Paper 派生算法） | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / 异步日志 | VMP mod | https://github.com/RelativityMC/Very-Many-Players |
| 动力铁轨优化（限界动力传播深度） | Fluorite server（PoweredRailsOptimized） | https://github.com/FluoritePowered/Fluorite-1.19.2 |
| 线程 CPU 开销剖析（PRTSThreadCost） | Youer server（YouerThreadCost） | https://github.com/MohistMC/Youer |
| 主线程卡顿 watchdog（WatchMohist） | Youer server（WatchMohist） | https://github.com/MohistMC/Youer |
| 控制台日志格式修复（FML 覆盖 log4j） | 自研，受 Youer 启发 | https://github.com/MohistMC/Youer |
| NearbyPlayerIndex（NPI）空间玩家索引 | 自研（基于 Paper AreaMap） | https://github.com/PaperMC/Paper |
| ClientModGuard 客户端模组预检 / 崩溃自愈（v27/v28） | 自研 | — |
| Guava 遮蔽崩溃修复（boot jar 排除 com.google.common） | 自研 | — |

## 构建

- 环境要求：JDK 21
- 命令：`./gradlew --no-daemon :bootstrap:neoforgeJar`（完整构建；**勿**跳过 `generateInstallerInfo`——缺失会导致启动 NPE）
- 产物：`bootstrap/build/libs/PRTS-neoforge-1.21.1-<version>.jar`
- 规则：boot jar 不得打包 `com.google.common`（旧 Guava 遮蔽平台 Guava；embed 阶段已排除）；仅对仓库同步的最终构建升版本号

## 部署

1. 复制新 jar 到服务端根目录，指向你的启动脚本（`_start.bat` 运行 `java -jar PRTS-neoforge-1.21.1-<version>.jar -nogui`）
2. 启动——内嵌 `common.jar` 内容变化时自动重解包，同名版本重建也会刷新；无需手动清理 `.arclight`

## 配置

- `prts.yml`（服务端根目录）：遗留优化（NPI、routeB、tracking 等）
- `config/servercore.yml`：ServerCore 六段（activation-range / breeding-cap / mob-spawning / features / dynamic / commands）；缺失段自动补全，启动时生效

## 兼容性

- NeoForge 模组 + Bukkit/Spigot 插件同服
- 可能与部分优化模组不兼容；与优化类 Bukkit 插件不兼容

## 许可

[GPL v3](LICENSE) 开源，与上游一致。
