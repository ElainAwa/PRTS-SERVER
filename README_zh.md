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

## 未来计划

以下为计划中的方向，不代表已实现或默认开启。所有新增的并行能力都会先以默认关闭的方式落地，经过本地合成负载验证与生产灰度后，再决定是否调整默认值。

### 区块加载多线程化

- **光照传播线程化**：把光照引擎从主线程移到独立线程。目前仅有每 tick 传播预算削峰，它能把大规模光照更新分摊到多个 tick，但工作仍留在主线程。
- **玩家距离优先的区块调度**：按玩家距离调度区块加载需求，替代先进先出队列，缩短登录、传送、随机传送后的等待时间。
- **长期方向：并行区块状态机**：参考 C2ME 项目的设计，重写原版区块状态推进链，让读盘、生成、安装等阶段在工作线程上并行执行。这需要改动 NeoForge 的区块状态机与相关事件时序，会在独立分支上开发验证后再合并。

### 区域并行引擎演进

- **不等宽条带**：当前实体按固定宽度的区块条带分区域；计划按各区域实际负载动态划分，缓解单区域过载拖慢整 tick 汇合等待的问题。
- **跨区引用观测与值快照**：采集实体跨区域读写的频率与热点，对高频只读值做任务级快照，减少跨区读取开销和被自动路由回主线程的实体比例。
- **区域权威副本（长期）**：为每个区域维护其区域数据的权威副本，消除跨区读取的竞态来源。该方案存在数据复制成本，需先完成跨区引用测量后评估是否落地。
- **确定性全序化（可选开关）**：跨区写入按统一顺序应用，默认关闭，作为排查并发问题的验证工具。

### 方块实体并行灰度

- 逐步开放更多方块实体类型在工作线程上 tick，从生产环境实测负载高的容器类方块实体开始。当前方块实体 tick 默认留在主线程，只有少量白名单类型在 worker 上运行。

### 合成负载与容量验证

- 继续用可复现的合成负载（大范围寻路实体、区块生成风暴、持续物品流转）衡量并行执行的实际收益，作为任何生产影子灰度上线的前置门槛。

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
