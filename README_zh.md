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
  pathfinding-async: true
  dimension-parallel: true
  region-parallel: true
  region-count: 4                # 2/4/8/16
  region-auto-scale: true
  
  # ThreadPolicy
  main-thread-routing: "auto"    # auto=学习模式，stats=统计不路由
  route-threshold: 5             # 窗口内触发路由的违规次数
  route-window-ticks: 2400       # 滑动窗口（2 分钟）
  
  # 路由学习持久化（v1.0.37+）
  persist-learned-routes: true
  learned-routes-file: "config/prts-learned-routes.json"
  learned-routes-limit: 200
  
  # Probation 自愈（v1.0.37+）
  route-probation-enabled: true
  route-probation-ticks: 12000   # 10 分钟
  route-probation-max-violations: 2
  
  # Routed entity drain 分批（v1.0.37+）
  main-thread-entity-drain-budget: 0  # 0=关闭
  
  # 村民 POI 寻路预算
  villager-poi-path-budget: 0    # 0=不限制，4-8 推荐

generation-tasks-per-tick: 50
chunkgen-inflight-limit: 128
barrier-watchdog-aware: true
barrier-timeout-ms: 120000
```

## 构建与部署

**环境**：JDK 21

**构建**：
```bash
./gradlew --no-daemon :bootstrap:neoforgeJar
```

**部署**：复制 `build/libs/PRTS-neoforge-1.21.1-<version>-Multithreading.jar` 到服务端根目录，启动。

**下载**：[GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER/releases)

## 最新更新

### v1.0.37 (2026-08-21)

- **S2.9 routed entity drain 优化**：主线程队列无预算直接 `while(poll())` 拖累 tick。新增遥测（drain queueDepth/barrier wait/per-region load）与分批机制（`main-thread-entity-drain-budget` 配置，默认 0=关闭）。
- **S3.1 路由学习持久化**：auto-routed 类重启丢失。Entry 改造（`routed` 改 AtomicBoolean，新增 `learnedTick` 字段），独立 JSON 文件（`config/prts-learned-routes.json`），启动加载 + 关服保存。
- **S3.2 双向 Probation**：auto-routed 类永久主线程丧失并行收益。定期 worker 试跑（默认 12000 ticks），无违规则 `clearRouted()` 恢复并行，有违规指数退避（2x/4x/8x）。ThreadLocal 隔离，遥测暴露 attempts/success/failed。
- **B4 传送带乘客修复**：Create 传送带 `tick()` 在 worker 注册乘客，`addPassenger` 抛 cross-thread NBT 断言致装置自毁。改 defer 主线程注册。
- **getPos 优化**：BE tick stats 热路径每次调 `getPos()` 新建 BlockPos。改仅新 max 时调用。

## 其他功能

与主分支 `1.21.1` 一致（ServerCore 移植、实体追踪、区块保存、异步日志、动力铁轨优化、客户端模组防护、红石/寻路优化等）。

## 许可

[GPL v3](LICENSE)，与上游一致。第三方功能版权与署名见 `THIRD-PARTY.md`。

## 鸣谢

- [Arclight](https://github.com/IzzelAliz/Arclight) - 原始 Hybrid 混合端
- [Luminara](https://github.com/CraftAmethyst/Luminara) - 上游 fork
- [ServerCore](https://github.com/Wesley1808/ServerCore) - 优化移植源
