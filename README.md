# PRTS-stable-Trials

[English](README_en.md)

一个私有的、面向生产环境加固的 [PRTS](https://github.com/QianMo0721/PRTS) 下游 fork
（PRTS 本身是 [Luminara](https://github.com/QianMo0721/Luminara) 的 fork，
而 Luminara 是 [Arclight](https://github.com/IzzelAliz/Arclight) 的 Hybrid fork），
目标平台 **Minecraft 1.20.1 / Forge 47.4.16**。

本 fork 面向多模组、多人的重载生产环境（Forge 模组与 Bukkit/Spigot 插件混合运行）。
定制改动遵循单一原则：

> **仅进行零感知的底层优化。**
> 不改变玩法：视距、生物量、刷怪规则、作物生长、区块刻均保持原版。
> 仅降低相同逻辑的资源开销，或修复崩溃。

> [!NOTE]
> 这是一个私有下游 fork。上游 PRTS 已停止更新，以下优化与崩溃修复均在此独立维护。
> 完整版本演进见 [CHANGELOG.md](CHANGELOG.md)。

## 环境

| 项目 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| 加载器 | Forge 47.4.16 |
| 基座 | Luminara（Arclight Hybrid fork）|
| JDK | 21（构建 & 运行）|
| 当前版号 | 见 [`build.gradle`](build.gradle) 的 `version` |

## 定制优化（本 fork）

各项优化均由 `prts.yml` 配置开关控制，设计目标为行为与原版一致、资源开销更低。
启动时以 `[PRTS-*]` 日志标签打印运行状态。

| 优化项 | 日志标签 | 说明 |
|---|---|---|
| **routeB 空间化实体追踪** | `[PRTS-EntityTrack]` | 空间化 `AreaMap` 追踪器（源自 HariPlayer）|
| **ticketpropagator** | `[PRTS-TP]` | Paper 式延迟 8 向区块 ticket 距离传播 |
| **ServerCore（12 项）** | — | 服务器 tick 的一组微优化 |
| **move-zero-velocity** | — | 零速度实体跳过冗余 `move()` |
| **async-logging** | — | log4j2 AsyncAppender 包裹根日志 |
| **NearbyPlayerIndex (NPI)** | `[PRTS-NPI]` | 空间索引加速最近玩家查找 `getNearestPlayer` / `hasNearbyAlivePlayer`（默认 `enabled=false`）|
| **核心原生调优** | — | chunk-load-rate-limit、并行世界初始化 + 异步数据加载、异步世界保存 |
| **崩溃修复（始终开启）** | `[PRTS-ChampionsFix]` | ChampionsConfig 惰性 bake；RevelationFix `inWhitelist` null 守卫 |

### NearbyPlayerIndex 安全模型

NPI 不介入区块发包路径，仅加速查询，并设有三重保障：

1. **原版记账为权威**——索引仅旁路加速，不覆盖发包逻辑。
2. **verify 双跑**（默认开启）——每次索引结果与原版对照，不一致时打 `WARN` 并采用原版结果。
3. **144 格数学守卫 + 异常自毒**——最坏情况为无加速，不会产生静默的错误结果。

## 构建

> [!IMPORTANT]
> 本 fork 的构建与部署有若干必须遵守的约束，构建前请先阅读。

**构建命令**（需 JDK 21）：

```bash
# 使用干净环境 + 锁定的 Gradle 8.14.5 + 隔离的临时目录
gradle --no-daemon collect --rerun-tasks
```

产物位于 `arclight-forge/build/libs/PRTS-1.20.1-<版号>.jar`。

**构建约束：**

- ✅ 版号（`build.gradle` 的 `version`）仅在同步至仓库的终版时更新；本地迭代构建不更改版号。
- ❌ 不要执行 `:arclight-forge:clean`——它会删除 reobf SRG 缓存，导致 refmap 损坏。
- ⚠️ 非 `@Mixin` 的辅助类不应放在 `mixin/...` 包下（会触发 `IllegalClassLoadError`），
  应置于 `io.izzel.arclight.common.optimization.general.<功能>` 下。
- ⚠️ 编写 mixin 前，先用 `javap` 对照编译后的 mojmap 类核对类名、字段与 INVOKE 属主，
  不要沿用更新 MC 版本的符号。

## 部署

1. 将新 jar 复制到服务端根目录，并让启动脚本指向它。
2. **强制重解包**——外层 jar 为启动器，实际类位于内部 `common.jar`。
   须同时清空两个缓存目录，否则将复用旧的 `common.jar`，导致修复不生效：

   ```bash
   rm -rf .arclight/mod_file/*
   rm -rf .arclight/class_cache/*
   ```

3. 启动服务器：

   ```bash
   java -jar PRTS-1.20.1-<版号>.jar nogui
   ```

## 兼容性

- 支持 Forge 模组与 Bukkit/Spigot 插件同时运行。
- 可能与部分优化模组不兼容；与优化类 Bukkit 插件不兼容。
- 本服已验证可共存的优化模组：ModernFix、FerriteCore、Canary、Saturn、KryptonReforged、
  Noisium、Radium (Lithium)、Immersive Optimization、MemoryLeakFix、PacketFixer、Spark。

## 致谢与协议

- 基于 IzzelAliz 的 [Arclight](https://github.com/IzzelAliz/Arclight) 构建。
- Fork 自 QianMo0721 的 [Luminara](https://github.com/QianMo0721/Luminara)（Arclight Hybrid fork）。
- 再 fork 为 PRTS（本仓库），定制优化与崩溃修复由 [ElainAwa](https://github.com/ElainAwa) 维护。

基于 [GPL v3](LICENSE) 开源，与上游一致。
