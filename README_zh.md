# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge（多线程并行分支）

**[English](./README.md) · [中文文档](./README_zh.md)**

> PRTS 是 [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara) 的 fork，本分支 `1.21.1-Multithreading` 为多线程并行引擎开发分支。

## 并行引擎

将单线程世界 tick 拆分为多线程执行：

- **异步寻路** (`pathfinding-async`)：怪物/村民寻路在工作线程计算
- **维度并行** (`dimension-parallel`)：主世界/下界/末地在独立线程 tick
- **区域并行** (`region-parallel`)：实体按区块条带划分为多个区域并行 tick
  - 可配置区域数（2/4/8/16，默认 4）
  - 自动负载均衡（`region-auto-scale`）
  - 玩家保持主线程（容器/菜单/事件）

### ThreadPolicy 自动学习

运行时检测线程访问违规（worker 调用 `getBlockEntity`/`setBlock` 等主线程 API），自动将不安全类路由到主线程：

- 滑动窗口统计（默认 2400 ticks 窗口，5 次违规触发路由）
- 路由学习持久化（`config/prts-learned-routes.json`，启动加载/关服保存）
- 双向 Probation：auto-routed 类定期 worker 试跑，无违规则恢复并行（指数退避 2x/4x/8x）
- 安全阀：worker 异常 → 永久降级主线程

### 区块生成削峰

- 提交预算（`generation-tasks-per-tick: 50`）
- 滚动窗口限流（`chunkgen-inflight-limit: 128`）
- 传送尖峰从 1.5-2.3s 降至 ~430ms

### Barrier 健壮性

- Watchdog 感知：并行等待时不误判超时
- 超时诊断（`barrier-timeout-ms: 120000`）：真卡死时 dump 后崩服

### Create 模组兼容

- 轨道 lazy-spread：跨 tick 摊开栅格化
- 传送带乘客延迟注册：防止 worker 竞态

## 配置

`prts-features.yml`（服务端根目录）：

```yaml
parallel:
  # ---- 并行引擎 ----
  pathfinding-async: true        # 异步寻路：怪物/村民寻路在工作线程计算，不占主线程
  dimension-parallel: true       # 维度并行：主世界/下界/末地在独立线程 tick，互不阻塞
  region-parallel: true          # 区域并行：非玩家实体按区块条带拆区并行 tick
  region-count: 4                # 区域数（2/4/8/16，默认 4）；区域越多并行度越高，但跨区开销也越大
  region-auto-scale: true        # 自动负载均衡：按主世界负载自动增减区域数

  # ---- ThreadPolicy 自动学习 ----
  # worker 线程访问主线程专属 API（getBlockEntity/setBlock 等）时记录违规，
  # 违规次数超阈值后自动把该实体类路由到主线程 tick，避免跨线程访问竞态。
  main-thread-routing: "auto"    # auto=学习模式（自动路由违规类）；stats=只统计不路由（观察用）
  route-threshold: 5             # 滑动窗口内触发路由的违规次数（越小越敏感）
  route-window-ticks: 2400       # 违规统计窗口大小（tick，2400=2 分钟）
  route-on-read: true            # MAIN_ONLY_READ 是否计入违规窗口；false=只按写路由（读为 null 安全降级）

  # ---- 路由学习持久化 ----
  # 自动学习到的路由（被路由到主线程的实体类）默认重启后丢失，需重新学习。
  # 开启后写入独立 JSON 文件，启动时自动恢复。
  persist-learned-routes: true   # 启用路由持久化（写入/读取 JSON）
  learned-routes-file: "config/prts-learned-routes.json"  # 持久化文件路径
  learned-routes-limit: 200      # 最多持久化的类数量（防文件膨胀）

  # ---- Probation 自愈 ----
  # 被自动路由的类可能只是暂时性违规，永久主线程会浪费并行度。
  # 开启后定期在 worker 上试跑该类实体：无违规则恢复并行，有违规则延长重试间隔。
  route-probation-enabled: true  # 启用自愈试跑
  route-probation-ticks: 12000   # 试跑间隔（tick，12000=10 分钟）
  route-probation-max-violations: 2  # 历史违规超过该值的类跳过试跑（高危类不试探）

  # ---- Routed entity drain 分批 ----
  # 被路由到主线程的实体队列默认一次性全部 tick，实体多时可能拖长单 tick。
  # 开启后每 tick 最多处理 N 个，剩余顺延到下 tick，摊平主线程压力。
  main-thread-entity-drain-budget: 0  # 每 tick 最多处理数（0=不限制，全量处理）

  # ---- 村民 POI 寻路预算 ----
  # 被路由到主线程的村民认领职业/寻路开销大，可限制每 tick 的寻路计算量。
  villager-poi-path-budget: 0    # 每 tick 寻路预算（0=不限制；卡顿时可试 4-8）

# ---- 区块生成削峰 ----
# 大量区块同时生成（传送/forceload）会压垮生成线程池造成主线程等待。
generation-tasks-per-tick: 50    # 主线程每 tick 提交的生成任务上限（0=不限制）
chunkgen-inflight-limit: 128     # 2 秒窗口内提交上限（防生成风暴尖峰）

# ---- Barrier 健壮性 ----
# 并行 tick 用 barrier 同步等待所有区域完成，需避免 watchdog 误杀与卡死。
barrier-watchdog-aware: true     # 并行等待时暂停 watchdog 计时，防止误判超时杀服
barrier-timeout-ms: 120000       # barrier 卡死超时（毫秒）；超时后 dump 全线程并崩服而非无限挂起
```

## 构建与部署

**环境**：JDK 21

**构建**：
```bash
./gradlew --no-daemon :bootstrap:neoforgeJar
```

**部署**：复制 `build/libs/PRTS-neoforge-1.21.1-*-Multithreading.jar` 到服务端根目录，启动。

**下载**：[GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)

## 其他功能

与主分支 `1.21.1` 一致（ServerCore 移植、实体追踪、区块保存、异步日志、动力铁轨优化、客户端模组防护、红石/寻路优化等）。

## 许可

[GPL v3](LICENSE)，与上游一致。第三方功能版权与署名见 `THIRD-PARTY.md`。

## 鸣谢

- [Arclight](https://github.com/IzzelAliz/Arclight) - 原始 Hybrid 混合端
- [Luminara](https://github.com/CraftAmethyst/Luminara) - 上游 fork
- [ServerCore](https://github.com/Wesley1808/ServerCore) - 优化移植源
