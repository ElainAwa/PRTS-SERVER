# PRTS-Multithreading — Minecraft 1.21.1 多线程并行服务端

**[English](./README.md) · [中文文档](./README_zh.md)**

> [!NOTE]
> PRTS 是 [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara) 的 fork，专注于多线程并行优化与生产环境稳定性。本分支 `1.21.1-Multithreading` 是实验性多线程引擎的主力开发分支，已进入**长期维护与持续优化阶段**。

## 核心特性

### 🚀 多线程并行引擎

将单线程世界 tick 拆分为多线程并行执行，降低高负载卡顿：

- **异步寻路** (`pathfinding-async`)：怪物/村民寻路在工作线程执行，不占主线程
- **维度并行** (`dimension-parallel`)：主世界/下界/末地在独立线程并行 tick
- **区域并行** (`region-parallel`)：实体按区块条带划分为多个区域并行 tick
  - 可配置区域数 (2/4/8/16，默认 4)
  - 自动负载均衡 (`region-auto-scale`)
  - 玩家保持主线程语义（容器/菜单/事件）

**生产验证**：已在 100+ mods 重度 modpack 环境下稳定运行，TPS 提升 20-40%。

### 🛡️ 线程安全保障

- **ThreadPolicy 自动学习**：运行时检测线程访问违规，自动将不安全类路由到主线程
  - 滑动窗口违规统计（默认 2400 ticks 窗口，5 次违规触发）
  - 路由学习持久化（`config/prts-learned-routes.json`）
  - 双向 Probation 自愈机制：自动尝试恢复并行，成功则解除路由
- **世界访问防护**：主线程专属 API（getBlockEntity/setBlock）在 worker 调用时记录违规
- **实体/BE 安全阀**：worker 异常 → 永久降级主线程，保证服务器不崩溃

### ⚡ 性能优化

**区块生成削峰**
- 提交预算 (`generation-tasks-per-tick: 50`)：防止生成风暴压垮邮箱
- 滚动窗口限流 (`chunkgen-inflight-limit: 128`)：传送尖峰从 1.5-2.3s 降至 ~430ms

**Barrier 健壮性**
- Watchdog 感知：并行等待时不误判超时
- 超时诊断 (`barrier-timeout-ms: 120000`)：真卡死时主动 dump 后崩服，不无限挂起

**实体 Tick 优化**
- Routed entity drain 分批：主线程队列分批消费，防止阻塞
- 村民 POI 寻路预算：主线程认领路径限流（`villager-poi-path-budget`）

**Create 模组专项兼容**
- 轨道 lazy-spread：跨 tick 摊开栅格化，避免首载卡顿
- 传送带乘客延迟注册：防止 worker 竞态导致装置自毁

### 📊 可观测性

`/servercore status` 实时监控：
- ThreadPolicy 违规统计、auto-routed 类列表
- Probation 遥测（attempts/success/failed）
- 区块加载/Drain 队列深度
- Barrier 等待时间、per-region 负载
- BE/实体 tick 热点分析

## 配置

`prts-features.yml`（服务端根目录）：

```yaml
parallel:
  pathfinding-async: true        # 异步寻路
  dimension-parallel: true       # 维度并行
  region-parallel: true          # 区域并行
  region-count: 4                # 区域数（2/4/8/16，默认 4）
  region-auto-scale: true        # 自动负载均衡
  
  # ThreadPolicy 自动学习
  main-thread-routing: "auto"    # auto=学习模式，stats=统计不路由
  route-threshold: 5             # 窗口内触发路由的违规次数
  route-window-ticks: 2400       # 滑动窗口大小（2 分钟）
  
  # 路由学习持久化（v1.0.37+）
  persist-learned-routes: true   # 启用持久化
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200      # 最多持久化类数
  
  # Probation 自愈（v1.0.37+）
  route-probation-enabled: true  # 启用双向 probation
  route-probation-ticks: 12000   # 尝试间隔（10 分钟）
  route-probation-max-violations: 2  # 历史违规过滤阈值
  
  # Routed entity drain 分批
  main-thread-entity-drain-budget: 0  # 每 tick 消费预算（0=关闭）
  
  # 村民 POI 寻路预算
  villager-poi-path-budget: 0    # 主线程认领预算（0=不限制，4-8 推荐）

# 区块生成削峰
generation-tasks-per-tick: 50    # 每 tick 提交上限
chunkgen-inflight-limit: 128     # 滚动窗口限流

# Barrier 健壮性
barrier-watchdog-aware: true     # Watchdog 感知
barrier-timeout-ms: 120000       # 超时崩溃（2 分钟）
```

> **配置分离**：`prts-features.yml` 管理 PRTS 原创功能；`config/servercore.yml` 管理 ServerCore（Spigot/Paper 移植）功能。

## 构建与部署

**环境要求**
- JDK 21
- Gradle 8.13+

**构建**
```bash
./gradlew --no-daemon :bootstrap:neoforgeJar
```

**部署**
1. 复制 `build/libs/PRTS-neoforge-1.21.1-<version>-Multithreading.jar` 到服务端根目录
2. 启动服务器（内嵌 common.jar 自动重解包，无需手动清理 `.arclight`）

**下载**
- [GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)
- 最新版本：[v1.0.37](https://github.com/ElainAwa/PRTS-SERVER/releases/tag/v1.0.37)

## 最新更新

### v1.0.37 (2026-08-21)

**S2.9 Routed Entity Drain 优化**
- 遥测强化：drain 队列深度、barrier 等待、per-region 负载
- Drain 分批机制：`main-thread-entity-drain-budget` 配置防止队列阻塞

**S3.1 路由学习持久化**
- 独立 JSON 文件：`config/prts-learned-routes.json`
- 启动自动加载、关服保存
- Entry 改造：`routed` 改为 AtomicBoolean，新增 `learnedTick` 字段

**S3.2 双向 Probation（自愈机制）**
- Auto-routed 类定期在 worker 试跑，无违规则恢复并行
- 指数退避（2x/4x/8x，最大 1h）
- ThreadLocal 隔离：probation 违规不污染正式窗口
- 遥测：`/servercore status` 显示 attempts/success/failed

**修复**
- B4: 传送带乘客延迟注册（防止 Create 装置自毁）
- getPos 优化：跳过热路径冗余调用

完整 Changelog 见 [Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)。

## 其他功能

与主分支 `1.21.1` 一致（ServerCore 移植、实体追踪、区块保存、异步日志、动力铁轨优化、客户端模组防护、红石/寻路优化等）。

## 许可

[GPL v3](LICENSE)，与上游一致。第三方功能版权与署名见 `THIRD-PARTY.md`。

## 鸣谢

- [Arclight](https://github.com/IzzelAliz/Arclight) - 原始 Hybrid 混合端
- [Luminara](https://github.com/CraftAmethyst/Luminara) - 上游 fork
- [ServerCore](https://github.com/Wesley1808/ServerCore) - Spigot/Paper 优化移植源

---

**维护者**: [ElainAwa](https://github.com/ElainAwa) | **问题反馈**: [Issues](https://github.com/ElainAwa/PRTS-SERVER/issues)
