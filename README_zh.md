# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge（多线程工程验证分支）

**[English](./README.md) · [中文文档](./README_zh.md)**

> [!WARNING]
> ⚠️ **此版本为工程验证构建，请谨慎使用，勿用于生产环境**。
> 本分支做了多线程并行的实验性改造，默认全开；如需回退到稳定行为，
> 在 `prts-features.yml` 中关闭对应开关（见下文「本分支独有配置」）。

> [!NOTE]
> **维护状态**：并行引擎功能已完整，本分支已进入**稳定维护与长期支持（LTS）阶段**——此后仅发布
> bug 修复与维护性更新，不再新增大型功能；如需新特性请走主分支路线。

> [!NOTE]
> 本文档由 AI 创作并维护，供快速上手与功能溯源。
> 本分支与主分支（`1.21.1`）**除多线程并行引擎外功能一致**——其余功能与完整功能溯源表
> **详见主分支 README**，此处不再赘述。

## 项目渊源

[Arclight](https://github.com/IzzelAliz/Arclight)（Hybrid 混合端）→ [Luminara](https://github.com/CraftAmethyst/Luminara) → **本 fork：PRTS**（[ElainAwa](https://github.com/ElainAwa) 维护）
→ **本分支：`1.21.1-Multithreading`**（在 PRTS 基础上叠加多线程并行引擎的实验分支）

## 本分支独有：多线程并行引擎

本分支将原本单线程的世界 tick 拆分为多个工作线程并行执行，以降低高负载下的卡顿。并行引擎由三部分组成，均可通过 `prts-features.yml` 独立开关（默认全开）：

- **异步寻路**（`pathfinding-async`）：怪物寻路在工作线程上执行，不占主线程；
- **维度并行**（`dimension-parallel`）：各维度在独立线程上 tick，互不阻塞；
- **区域并行**（`region-parallel`）：主世界按区块条带划分为多个区域并行 tick；区域数由 `region-count` 配置（2/4/8，默认 4），`region-auto-scale` 按负载自动增减。

### 生成风暴削峰与 Barrier 健壮性（v1.0.29）

生成风暴易压垮 worldgen 邮箱、卡死并行 barrier。两道预算与两道 watchdog 钩子保证引擎响应性：

- **区块生成提交预算**（`generation-tasks-per-tick`，默认 50）：主线程每 tick 最多向 worldgen 邮箱提交 N 个生成任务——高负载下平均 MSPT 降约 20%。
- **区块生成提交时间窗**（`chunkgen-inflight-limit`，默认 128）：滚动 2s 窗口内最多提交 N 个，worldgen 完成回调匀速到达——传送/forceload 风暴的 max 尖峰从 ~1.5–2.3s 降至 ~430ms。
- **watchdog 感知 Barrier**（`barrier-watchdog-aware`，默认开）：主线程在并行 barrier 内等待时，原版 watchdog 不再误杀。
- **Barrier 超时诊断**（`barrier-timeout-ms`，默认 120000）：barrier 真卡死时主动全线程 dump 后崩服，不再无限挂起。

### 统一异步区块调度（v1.0.30）

防止并行引擎在重 modpack 上引发服务器挂起。效果：并行开启下，世界 tick 保持响应，区块生成不再因负载而死锁。

## 本分支独有配置（`prts-features.yml`，服务端根目录）

```yaml
# 多线程并行引擎（默认全开；false 关闭对应能力，回退单线程行为）
parallel:
  pathfinding-async: true        # 异步寻路
  dimension-parallel: true       # 维度并行
  region-parallel: true          # 区域并行
  region-count: 4                # 区域数（2/4/8，默认 4；非 2 的幂自动回落 4）
  region-auto-scale: true        # 按负载自动增减区域数
  region-scale-interval-seconds: 300
  region-scale-high-mspt: 60
  region-scale-low-mspt: 15
  region-scale-stable-periods: 2
  region-scale-min: 2
  region-scale-max: 8
  region-scale-cross-read-ratio: 0.05
# 可靠区块保存（WAL 预写日志，默认关）
reliable-chunk-save:
  enabled: false
  interval-seconds: 30
  chunks-per-tick: 50
# 区块生成削峰（v1.0.29；0 = 关闭）
generation-tasks-per-tick: 50
chunkgen-inflight-limit: 128
# Barrier 健壮性（v1.0.29）
barrier-watchdog-aware: true
barrier-timeout-ms: 120000
```

> 配置归属说明：并行引擎与 WAL 属 **PRTS 原创功能**，归 `prts-features.yml`；
> `config/servercore.yml` 仅保留 ServerCore（Spigot/Paper 移植）功能，两者职责分离。

## 版本与产物

- 当前版本：**v1.21.1-1.0.30-Multithreading**
- 构建产物：**`PRTS-neoforge-1.21.1-<版号>-Multithreading.jar`**（后缀 `-Multithreading` 标识工程验证构建）

## 构建与部署

- 环境：JDK 21；命令：`./gradlew --no-daemon :bootstrap:neoforgeJar`
- 部署：复制 jar 到服务端根目录 → **清空 `.arclight/mod_file/*`**（强制重解包内嵌 common.jar）→ 启动
- 完整说明与主分支一致，**详见主分支 README**

## 其余功能

与主分支（`1.21.1`）一致（ServerCore 移植、实体追踪、区块保存、异步日志、动力铁轨优化、
客户端模组防护、红石/寻路优化等）——**完整功能表与溯源详见主分支 README**。

## 许可

基于 [GPL v3](LICENSE) 开源，与上游一致；第三方功能版权与署名见 `THIRD-PARTY.md`。
