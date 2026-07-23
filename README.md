# Luminara-stable-Trials

[English](README_en.md)

一个私有的、面向生产环境加固的 [Luminara](https://github.com/QianMo0721/Luminara) 下游 fork
（Luminara 本身是 [Arclight](https://github.com/IzzelAliz/Arclight) 的 Hybrid fork），
目标平台 **Minecraft 1.20.1 / Forge 47.4.16**。

本 fork 面向**多模组 + 多人**的重载生产环境调优（约 250 个模组，多人同时在线，Forge 模组 + Bukkit/Spigot 插件混合运行）。
所有定制改动只遵循一条原则：

> **只做零感知的底层优化。**
> 绝不改变玩法——视距、生物量、刷怪规则、作物生长、区块刻全部保持原版。
> 我们只让*同样的工作*变得更便宜，或者修复崩溃。

> [!NOTE]
> 这是一个**私有**下游 fork。上游 Luminara 已永久停更；下方记录的所有优化与崩溃修复均在此独立维护。
> 完整版本演进见 [CHANGELOG.md](CHANGELOG.md)。

## 环境

| 项目 | 值 |
|---|---|
| Minecraft | 1.20.1 |
| 加载器 | Forge 47.4.16 |
| 基座 | Arclight Hybrid（Luminara fork）|
| JDK | 21（构建 & 运行）|
| 当前版号 | 见 [`build.gradle`](build.gradle) 的 `version` |

## 定制优化（本 fork）

所有优化都由 `luminara.yml` 的配置开关控制，设计上**行为与原版完全一致**但更省资源。
启动时以 `[Luminara-*]` 日志标签打印运行状态。

| 优化项 | 日志标签 | 说明 |
|---|---|---|
| **routeB 空间化实体追踪** | `[Luminara-EntityTrack]` | 空间化 `AreaMap` 追踪器（源自 HariPlayer）|
| **ticketpropagator** | `[Luminara-TP]` | Paper 式延迟 8 向区块 ticket 距离传播 |
| **ServerCore（12 项）** | — | 服务器 tick 的一组微优化 |
| **move-zero-velocity** | — | 零速度实体跳过冗余 `move()` |
| **async-logging** | — | log4j2 AsyncAppender 包裹根日志 |
| **NearbyPlayerIndex (NPI)** | `[Luminara-NPI]` | 空间索引加速最近玩家查找 `getNearestPlayer` / `hasNearbyAlivePlayer`（默认 `enabled=false`）|
| **核心原生调优** | — | chunk-load-rate-limit、并行世界初始化 + 异步数据加载、异步世界保存 |
| **崩溃修复（始终开启）** | `[Luminara-ChampionsFix]` | ChampionsConfig 惰性 bake；RevelationFix `inWhitelist` null 守卫 |

### NearbyPlayerIndex 安全模型

NPI 绝不触碰区块发包路径，只**加速查询**，并有三重保险：

1. **原版记账永远权威**——索引只旁路加速，绝不覆盖发包逻辑。
2. **verify 双跑**（默认开）——每次索引结果都与原版对照，不一致即打 `WARN` 并采用原版结果。
3. **144 格数学守卫 + 异常自毒**——最坏情况只是"没加速"，绝不可能出现静默的错误答案。

## 构建

> [!IMPORTANT]
> 本 fork 有一套用血泪换来的构建/部署铁律，动手前务必阅读。

**构建命令**（需 JDK 21）：

```bash
# 使用干净环境 + 锁定的 Gradle 8.14.5 + 隔离的临时目录
gradle --no-daemon collect --rerun-tasks
```

产物位于 `arclight-forge/build/libs/luminara-1.20.1-<版号>.jar`。

**构建铁律：**

- ✅ **每次**新构建都必须升 `build.gradle` 里的 `version`。
- ❌ **不要**执行 `:arclight-forge:clean`——它会删掉 reobf SRG 缓存，导致 refmap 损坏。
- ⚠️ 非 `@Mixin` 的辅助类**绝不能**放在 `mixin/...` 包下（会触发 `IllegalClassLoadError`），
  应放在 `io.izzel.arclight.common.optimization.general.<功能>` 下。
- ⚠️ 写任何 mixin 前，先用 `javap` 对照编译后的 mojmap 类核对类名/字段/INVOKE 属主——
  不要照搬更新 MC 版本的符号。

## 部署

1. 把新 jar 复制到服务端根目录，并让启动脚本指向它。
2. **强制重解包**——外层 jar 只是启动器，真正的类在内部 `common.jar` 里。
   **必须**同时清空两个缓存目录，否则会复用旧的 `common.jar`，你的修复会静默不生效：

   ```bash
   rm -rf .arclight/mod_file/*
   rm -rf .arclight/class_cache/*
   ```

3. 启动服务器：

   ```bash
   java -jar luminara-1.20.1-<版号>.jar nogui
   ```

## 兼容性

- 支持 Forge 模组与 Bukkit/Spigot 插件同时运行。
- 可能不兼容部分优化模组；与优化类 Bukkit 插件不兼容。
- 本服已验证可共存的优化模组：ModernFix、FerriteCore、Canary、Saturn、KryptonReforged、
  Noisium、Radium (Lithium)、Immersive Optimization、MemoryLeakFix、PacketFixer、Spark。

## 致谢与协议

- 基于 IzzelAliz 的 [Arclight](https://github.com/IzzelAliz/Arclight) 构建。
- Fork 自 QianMo0721 的 [Luminara](https://github.com/QianMo0721/Luminara)。
- 本 fork 的定制优化与崩溃修复由 [ElainAwa](https://github.com/ElainAwa) 维护。

基于 [GPL v3](LICENSE) 开源，与上游一致。
