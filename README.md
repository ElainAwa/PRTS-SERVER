# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge（多线程工程验证分支）

> [!WARNING]
> ⚠️ **此版本为工程验证构建，请谨慎使用，勿用于生产环境**。
> 分支基于多线程并行引擎（P1/P2/P3）的实验性改造，默认全开；如需回退到稳定行为，
> 在 `prts-features.yml` 中显式关闭对应开关（见下文「本分支独有配置」）。

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源。
> 本分支与主分支（`1.21.1`）**除多线程并行引擎外功能一致**——其余功能与完整功能溯源表
> **详见主分支 README**，此处不再赘述。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/CraftAmethyst/Luminara) → **本 fork：PRTS**（[ElainAwa](https://github.com/ElainAwa) 维护）
→ **本分支：`p3-parallel`**（在 PRTS 基础上叠加多线程并行引擎的实验分支）

## 本分支独有：多线程并行引擎

将单主线程 tick 拆分为多 worker 并行，三个层次可独立开关（`prts-features.yml`，默认全开）：

| 层级 | 能力 | 说明 | 设计文档 |
|---|---|---|---|
| **P1** | 异步寻路（pathfinding-async） | PathFinder A* 提交工作线程计算，结果下一 tick 应用；区域 worker 内按 region 分桶提交、本区 drain | `docs/parallel-phase2-dimension-parallelism-v01.md` 等 |
| **P2** | 维度并行（dimension-parallel） | 各维度独立 worker tick，主线程 tick 屏障同步，跨维度传送延迟到屏障 | `docs/parallel-phase2-dimension-parallelism-v01.md` |
| **P3** | 区域并行（region-parallel） | 主世界按 chunk 条带分 2 区域，方块/实体/TE tick 分三会话在区域 worker 并行；跨区写走更新集协议（1 tick 窗口）、实体管理两级锁、跨区读写计数仪表盘 | `docs/parallel-phase3-region-parallelism-v01.md` ~ `v10` |

**已固化在本分支的关键修复**（主分支没有）：
- **mob AI 冻结修复**：NeoForge 1.21.1 将 AI 调度移入 `serverAiStep()`（仅主线程 consumer 调用），区域 worker 曾静默丢 AI——已补调（commit `f63a294`）
- **实体管理线程安全化**：EntityLookup/EntityTickList/EntitySectionStorage/EntitySection 两级锁（commit `5edd04a` 前置的 v05）
- **跨区红石统计**：`cross.redstoneBoundary` 边界带计数，实测跨区红石 <1%，红石子求解永久免做（commit `7f24b2f`）

## 本分支独有配置（`prts-features.yml`，服务端根目录）

```yaml
# 多线程并行引擎（默认全开；false 关闭回退单线程原版行为）
parallel:
  pathfinding-async: true
  dimension-parallel: true
  region-parallel: true
# 可靠区块保存（WAL 预写日志，默认关）
reliable-chunk-save:
  enabled: false
  interval-seconds: 30
  chunks-per-tick: 50
```

> 配置归属说明：并行引擎与 WAL 属 **PRTS 原创功能**，归 `prts-features.yml`；
> `config/servercore.yml` 仅保留 ServerCore（Spigot/Paper 移植）功能，两者职责分离。

## 版本与产物

- 当前版本：**v1.21.1-1.0.25**（与主分支同步的版本号）
- 构建产物：**`PRTS-neoforge-1.21.1-<版号>-Multithreading.jar`**（后缀 `-Multithreading` 标识工程验证构建）
- 演进记录：本分支 commits（见 git log）

## 构建与部署

- 环境：JDK 21；命令：`./gradlew --no-daemon :bootstrap:neoforgeJar`
- 部署：复制 jar 到服务端根目录 → **清空 `.arclight/mod_file/*`**（强制重解包内嵌 common.jar）→ 启动
- 完整说明与主分支一致，**详见主分支 README**

## 其余功能

与主分支（`1.21.1`）一致：ServerCore 完整移植六段、routeB 空间化实体追踪、ticketpropagator、
move-zero-velocity / async-logging、动力铁轨优化、PRTSThreadCost、WatchMohist、NPI、
ClientModGuard、Guava 遮蔽修复、neighbor 更新熔断、ae2lt 节流等——
**完整功能表与溯源详见主分支 README**。

## 许可

基于 [GPL v3](LICENSE) 开源，与上游一致；第三方功能版权与署名见 `THIRD-PARTY.md`。
