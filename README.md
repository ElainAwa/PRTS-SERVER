# Luminara-stable-Trials

一个私有的、面向生产环境加固的 [Luminara](https://github.com/QianMo0721/Luminara) 下游 fork
（Luminara 本身是 [Arclight](https://github.com/IzzelAliz/Arclight) 的 Hybrid fork），
目标平台 **Minecraft 1.21.1 / NeoForge**。

本 fork 面向多模组、多人的重载生产环境（NeoForge 模组与 Bukkit/Spigot 插件混合运行）。
定制改动遵循单一原则：

> **仅进行零感知的底层优化。**
> 不改变玩法：视距、生物量、刷怪规则、作物生长、区块刻均保持原版。
> 仅降低相同逻辑的资源开销，或修复崩溃。

> [!NOTE]
> 这是一个私有下游 fork。上游 Luminara 已停止更新，以下优化与崩溃修复均在此独立维护。
> 与 1.20.1 版本（同仓库 `master` / `develop` 分支）通过分支区分，本分支为 `1.21.1`。

## 环境

| 项目 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.x |
| 基座 | Arclight Hybrid（Luminara fork，NeoForge 分支）|
| JDK | 21（构建 & 运行）|
| 当前版号 | 见 [`build.gradle`](build.gradle) 的 `version` |

## 定制优化（本 fork）

各项优化均由 `luminara.yml` 配置开关控制，设计目标为行为与原版一致、资源开销更低。
启动时以 `[Luminara-*]` 日志标签打印运行状态。

| 优化项 | 日志标签 | 说明 |
|---|---|---|
| **routeB 空间化实体追踪** | `[Luminara-EntityTrack]` | 空间化 `AreaMap` 追踪器（源自 HariPlayer）|
| **ticketpropagator** | `[Luminara-TP]` | Paper 式延迟 8 向区块 ticket 距离传播 |
| **ServerCore（12 项）** | — | 服务器 tick 的一组微优化 |
| **move-zero-velocity** | — | 零速度实体跳过冗余 `move()` |
| **async-logging** | `[Luminara-AsyncLog]` | log4j2 AsyncAppender 包裹根日志 |
| **核心原生调优** | — | chunk-load-rate-limit、并行世界初始化 + 异步数据加载、异步世界保存 |
| **崩溃修复（构建期，始终开启）** | — | 修复 boot jar 打包旧 Guava 21.0 遮蔽平台 Guava 32.1.2，导致依赖新版 Guava 的模组 `NoSuchMethodError` 崩溃 |

> [!NOTE]
> `NearbyPlayerIndex (NPI)` 尚未移植到 1.21.1 分支；如需要可参照 1.20.1 分支实现。

## 构建

> [!IMPORTANT]
> 本 fork 的构建与部署有若干必须遵守的约束，构建前请先阅读。

**构建命令**（需 JDK 21）：

```bash
gradle --no-daemon collect
```

产物位于 `build/libs/luminara-neoforge-1.21.1-<版号>.jar`。

**构建约束：**

- ✅ 版号（`build.gradle` 的 `version`）仅在同步至仓库的终版时更新；本地迭代构建不更改版号。
- ❌ **不要** `collect -x :bootstrap:generateInstallerInfo`——该任务生成 `META-INF/installer.json`，缺失会导致启动 `InputStreamReader(null)` NPE。必须完整 `collect`。
- ⚠️ 非 `@Mixin` 的辅助类不应放在 `mixin/...` 包下（会触发 `IllegalClassLoadError`），
  应置于 `io.izzel.arclight.common.optimization.general.<功能>` 下。
- ✅ boot jar 不得打包 `com.google.common`（旧 Guava 21.0 会遮蔽平台 Guava 32.1.2）；
  已在 `bootstrap/build.gradle` 的 embed 解包阶段 `exclude 'com/google/common/**'`，运行期统一走平台 Guava。

## 部署

1. 将新 jar 复制到服务端根目录，并让启动脚本指向它（推荐用 `.run.bat` 的 `java -jar luminara-neoforge-1.21.1-<版号>.jar -nogui` 启动）。
2. **强制重解包**——外层 jar 为启动器，实际类位于内部 `common.jar`。
   须同时清空两个缓存目录，否则将复用旧的 `common.jar`，导致修复不生效：

   ```bash
   rm -rf .arclight/mod_file/*
   rm -rf .arclight/class_cache/*
   ```

3. 启动服务器：

   ```bash
   java -jar luminara-neoforge-1.21.1-<版号>.jar nogui
   ```

> [!NOTE]
> log4j2 配置文件在首次启动时提取到 `.arclight/arclight-log4j2.xml`，不会污染服务端根目录；
> 同时已抑制 `package scanning` 废弃 WARN。

## 兼容性

- 支持 NeoForge 模组与 Bukkit/Spigot 插件同时运行。
- 可能与部分优化模组不兼容；与优化类 Bukkit 插件不兼容。

## 致谢与协议

- 基于 IzzelAliz 的 [Arclight](https://github.com/IzzelAliz/Arclight) 构建。
- Fork 自 QianMo0721 的 [Luminara](https://github.com/QianMo0721/Luminara)。
- 本 fork 的定制优化与崩溃修复由 [ElainAwa](https://github.com/ElainAwa) 维护。

基于 [GPL v3](LICENSE) 开源，与上游一致。
