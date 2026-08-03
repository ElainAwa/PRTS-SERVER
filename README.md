# PRTS-stable-Trials — Minecraft 1.21.1 / NeoForge

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源；源码与移植细节见仓库 `docs/` 目录。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/QianMo0721/Luminara) → [PRTS](https://github.com/QianMo0721/PRTS) → **本 fork**（[ElainAwa](https://github.com/ElainAwa) 维护，私有生产加固）

## 定位

多模组、多人重载生产环境专用：**NeoForge 模组与 Bukkit/Spigot 插件同服运行**。
只做零感知的底层优化与崩溃修复 —— 不改玩法，仅降低同类逻辑的资源开销。

## 功能与来源

> 原模组无法兼容 Luminara (arclight) 基座，为避免装模组引发的冲突与事故，已将对应代码并入核心（去模组化重写），并入核心主要是图省事，修改核心来兼容这两个模组同时运行需要动很多东西，不太划算。

| 功能 | 来源 | 仓库 |
|---|---|---|
| ServerCore 完整移植六段（activation-range / breeding-cap / mob-spawning / features / dynamic / commands） | 移植自 ServerCore 模组 | https://github.com/Wesley1808/ServerCore |
| routeB 空间化实体追踪 | 移植自 VMP 模组（AreaMap 算法） | https://github.com/RelativityMC/Very-Many-Players |
| ticketpropagator（延迟 8 向区块 ticket 传播） | 移植自 VMP（源自 Paper 算法） | https://github.com/RelativityMC/Very-Many-Players |
| move-zero-velocity / async-logging | 移植自 VMP 模组 | https://github.com/RelativityMC/Very-Many-Players |
| 动力铁轨优化（供电传播限定深度） | 移植自 Fluorite 服务端（PoweredRailsOptimized） | https://github.com/FluoritePowered/Fluorite-1.19.2 |
| 线程 CPU 耗时剖析（PRTSThreadCost） | 移植自 Youer 服务端（YouerThreadCost） | https://github.com/MohistMC/Youer |
| 主线程卡顿看门狗（WatchMohist） | 移植自 Youer 服务端（WatchMohist） | https://github.com/MohistMC/Youer |
| 控制台日志格式修复（FML 覆盖 log4j） | 参考 Youer 思路自研 | https://github.com/MohistMC/Youer |
| NearbyPlayerIndex（NPI）最近玩家空间索引 | 本 fork 自研（基于 Paper AreaMap 数据结构） | https://github.com/PaperMC/Paper |
| ClientModGuard 客户端模组预检 / 崩溃自愈（v27/v28） | 本 fork 自研 | — |
| Guava 遮蔽崩溃修复（boot jar 不打包 com.google.common） | 本 fork | — |

## 版本

- 当前：**v1.21.1-1.0.16**（GitHub Releases 获取；Latest 跟随最新发布）
- 演进记录：GitHub Releases 各版本说明

## 构建

- 环境：JDK 21
- 命令：`./gradlew --no-daemon :bootstrap:neoforgeJar`（完整构建，**勿**跳过 `generateInstallerInfo`——缺失会启动 NPE）
- 产物：`bootstrap/build/libs/PRTS-neoforge-1.21.1-<版号>.jar`
- 约束：boot jar 不得打包 `com.google.common`（旧 Guava 遮蔽平台 Guava，已在 embed 阶段 exclude）；版号仅在同步仓库的终版更新

## 部署

1. 复制新 jar 到服务端根目录，启动脚本指向它（`_start.bat` 的 `java -jar PRTS-neoforge-1.21.1-<版号>.jar -nogui`）
2. **强制重解包**：外层 jar 是启动器，实际类在内部 `common.jar` —— 必须清空 `.arclight/mod_file/*`，否则复用旧 common 不生效
3. 启动：`java -jar PRTS-neoforge-1.21.1-<版号>.jar nogui`

## 配置

- `prts.yml`（服务端根目录）：既有优化（NPI、routeB、tracking 等）开关
- `config/servercore.yml`：ServerCore 六段开关（activation-range / breeding-cap / mob-spawning / features / dynamic / commands），缺失段启动时自动补写并生效

## 兼容性

- NeoForge 模组 + Bukkit/Spigot 插件同服运行
- 可能与部分优化模组不兼容；与优化类 Bukkit 插件不兼容

## 许可

基于 [GPL v3](LICENSE) 开源，与上游一致。
