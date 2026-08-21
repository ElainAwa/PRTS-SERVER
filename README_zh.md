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
  pathfinding-async: true        # 异步寻路：寻路在工作线程计算
  dimension-parallel: true       # 维度并行：各维度独立线程 tick
  region-parallel: true          # 区域并行：非玩家实体按区块条带并行 tick
  region-count: 4                # 区域数（2/4/8/16）
  region-auto-scale: true        # 按负载自动增减区域数

  # ThreadPolicy：worker 访问主线程专属 API 时自动把违规实体类路由到主线程
  main-thread-routing: "auto"    # auto=自动路由；stats=只统计不路由
  route-threshold: 5             # 窗口内触发路由的违规次数
  route-window-ticks: 2400       # 违规统计窗口（tick，2400=2 分钟）
  route-on-read: true            # 读违规是否计入窗口；false=只按写路由

  # 路由持久化：学习到的路由写入 JSON，重启后恢复
  persist-learned-routes: true   # 启用持久化
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200      # 最多持久化类数

  # Probation 自愈：被路由的类定期在 worker 试跑，无违规恢复并行
  route-probation-enabled: true  # 启用自愈
  route-probation-ticks: 12000   # 试跑间隔（tick）
  route-probation-max-violations: 2  # 历史违规超过该值不试跑

  # Routed entity drain 分批：限制主线程每 tick 处理的 routed 实体数
  main-thread-entity-drain-budget: 0  # 每 tick 最多处理数（0=不限制）

  # 村民 POI 寻路预算：限制主线程村民寻路量
  villager-poi-path-budget: 0    # 每 tick 预算（0=不限制，卡顿时试 4-8）

# 区块生成削峰
generation-tasks-per-tick: 50    # 每 tick 提交生成任务上限
chunkgen-inflight-limit: 128     # 2 秒窗口提交上限

# Barrier 健壮性
barrier-watchdog-aware: true     # 并行等待时暂停 watchdog，防误杀
barrier-timeout-ms: 120000       # barrier 卡死超时（毫秒）
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
