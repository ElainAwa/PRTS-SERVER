# ClientModGuard v23 — 客户端自检迁移到 1.21.1

> 状态：方案文档（先文档后代码）。未动任何源码，待用户确认后实施。
> 命名约定：本次迭代记 `clientmodguard-v23`（1.21.1 侧的 v22 等价物），与 1.20.1 的 v22 同源同代。

---

## 1. 需求

把 1.20.1 已稳定的 **ClientModGuard v22（真·总开关）** 完整迁移到 1.21.1（NeoForge）分支，使两个分支的客户端模组自检**行为、配置、路径完全一致**：

- `autoQuarantine` 作为**真·总开关**：`false`（默认）= 整套自检（预检/隔离/自愈）全部关闭；`true` = 完整启用。
- 配置唯一落点 `_clientcheck/guard.yml`（取代 1.21.1 当前的 `clientside-guard.json` + `prts.yml` 旧体系）。
- 隔离区统一为 `_disabled_mods/`（取代 1.21.1 当前的 `_quarantine/clientside/`）。
- 补齐 v22 全部判据：中毒 mixin 五条判据、守护者归因 + 家族连坐、`custom_fingerprints.json`、`launch.args` 持久化、状态记忆（`_clientcheck/state.json`）。

## 2. 实证（两分支现状对比）

| 维度 | 1.20.1（源，v22，1.0.54） | 1.21.1（目标，v10，1.0.5） |
|------|---------------------------|----------------------------|
| 文件路径 | `arclight-forge/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java` | `bootstrap/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java` |
| 包 / 类名 | `io.izzel.arclight.server.ClientModGuard` | 同 |
| `autoQuarantine` 默认值 | **`false`**（真·总开关） | `true`（L1097） |
| `run()` 门控 | `if(cfg.autoQuarantine){ scan(); } else 打印"自检已关闭"`（L312-315） | 无门控，无条件 `scan()` |
| 配置来源 | `_clientcheck/guard.yml`（含 `autoQuarantine`/`maxRestarts`/`allowlist`/`denylist`） | `clientside-guard.json` + 兼容 `prts.yml guard.allowlist` |
| 隔离区 | `_disabled_mods/` | `_quarantine/clientside/`（L42 `QUARANTINE_DIR`） |
| 中毒 mixin 判据 | ✅ 五条（detectPoisonMixin） | ❌ 无 |
| 守护者归因 + 家族连坐 | ✅ resolveGuardianCulprit | ❌ 无 |
| `custom_fingerprints.json` | ✅ 外部增量指纹 | ❌ 无 |
| `launch.args` 持久化 | ✅ | ❌ 无 |
| 自愈接线 | `run()` 内 `addShutdownHook` + `setDefaultUncaughtExceptionHandler`（L326/L338） | 仅 `handleCrash`（Launcher 调） |
| import 依赖 | **纯 JDK**（无 Forge 47 专有类） | 纯 JDK |

**Launcher 集成（关键）**：两分支 `Launcher.java` 完全相同——只调 `ClientModGuard.run(args)`（L18/L15）与 `ClientModGuard.handleCrash(t, args)`（L44/L34）。v22 的 `shutdownHeal`/`onUncaughtClientFailure` 在 `run()` 内部自行接线，**不依赖 Launcher 改动**。

**树内引用**：1.21.1 全树仅 `ClientModGuard.java` 与 `Launcher.java` 引用该体系，无其他代码依赖 `_quarantine`/`clientside-guard.json` → 整体覆盖文件不会断链。

## 3. 根因

1.21.1 落在 v10 架构，早于 1.20.1 的 v22 演化。两分支各自演进导致：
- 配置/隔离区/判据三套不一致，服主需维护两套认知；
- 1.21.1 缺中毒 mixin 预拦截与守护者归因，面对"客户端类缺失崩服"防护弱于 1.20.1；
- 1.21.1 默认 `autoQuarantine=true` 且无总开关语义，关不掉整套自检。

## 4. 备选方案对比

- **方案 A（选定）：整体复制 1.20.1 v22 文件覆盖 1.21.1**
  - 优点：一键对齐两分支；Launcher 零改动（集成已一致）；纯 JDK 依赖，applaunch `-source 7` 约束已满足；配置/路径/判据全统一；默认 `false` 真·总开关直接生效。
  - 风险：极低（见 §7）。
- **方案 B：仅把 v22 的"总开关门控"移植进 1.21.1 现有 v10**
  - 优点：改动最小。
  - 缺点：保留 `clientside-guard.json`/`prts.yml`/`_quarantine` 旧体系，两版本仍不统一；v10 缺中毒 mixin 判据/守护者归因/launch.args，防护不完整；治标不治本。
- **方案 C：把 1.21.1 的 v10 特性反向合入 1.20.1**
  - 已无意义（1.20.1 已 v22 更全）。

## 5. 选定方案与代码位置

**方案 A**。具体步骤：

1. **复制文件**（核心动作）
   - 源：`D:\mc\PRTS-SERVER\1.20.1\Luminara-stable-Trials\arclight-forge\src\applaunch\java\io\izzel\arclight\server\ClientModGuard.java`（v22，~2280 行）
   - 目标：覆盖 `D:\mc\PRTS-SERVER\1.21.1\Luminara-FeudalKings\bootstrap\src\applaunch\java\io\izzel\arclight\server\ClientModGuard.java`
   - 包名 `io.izzel.arclight.server`、类名 `ClientModGuard` 完全一致，直接覆盖。
2. **升版号**
   - `D:\mc\PRTS-SERVER\1.21.1\Luminara-FeudalKings\build.gradle` 第 14 行 `version '1.0.5'` → `version '1.0.6'`（1.21.1 独立版本线，不与 1.20.1 的 1.0.54 混）。
3. **Launcher：无需改动**（集成已一致，自愈接线随文件一并带入）。
4. **（构建/发版，待授权）** 见 §6。

> 注：复制后 1.21.1 的 `autoQuarantine` 默认即 `false`（v22 语义），与 1.20.1 统一。若希望 1.21.1 首发即开，可在测服 `_clientcheck/guard.yml` 显式设 `true`（首次启动自动生成后再改）。

## 6. 验收

1. **编译**：1.21.1 用 `:bootstrap:neoforgeJar --offline` → 根 `:collect --offline`，产出 `PRTS-1.21.1-1.0.6.jar`，`BUILD SUCCESSFUL`。
   - ⚠️ 本环境 `gradlew.bat`/`cmd` 失效（JAVA_HOME 含空格、缺 cygpath），沿用 1.20.1 的直启 gradle CLI jar 方案：取 gradle-8.13 dist（`~/.gradle/wrapper/dists/gradle-8.13-all/...`）的 `gradle-gradle-cli-main-8.13.jar` + `agents/gradle-instrumentation-agent-8.13.jar`，`JAVA_HOME` 用 Windows 反斜杠路径，`java -javaagent:... -jar ...`。
2. **产物校验**（Python 读 jar 内 `ClientModGuard.class`）：应包含串 `自检已关闭` / `autoQuarantine` / `_clientcheck` / `guard.yml` / `_disabled_mods`，证明 v22 逻辑已编译进 jar。
3. **真机双模式**（用户或我，临时 `eula=false` 快速退出抓日志）：
   - `autoQuarantine=false`：仅打印 `[PRTS] 客户端模组自检已关闭 (autoQuarantine=false)...`，无 scan、零挪模。
   - `autoQuarantine=true`：完整跑扫描 → 判定 → 隔离客户端模组 → `隔离 N 个 / 保留 M 个，继续启动`。

## 7. 风险与回滚

**风险**
- **R1（低，需验证）**：v22 的 `clientSideOnly` 启发式读 `mods.toml` 根级 `clientSideOnly=true`（Forge 47 约定 `__FORGE_clientSideOnly`）；NeoForge 1.21.1 用 `neoforge.mods.toml`，`clientSideOnly` 字段是否同名同义待实机确认。此为**次要信号**（主检测是 mixin/指纹扫描），即使漏读也不影响核心防护。
- **R2（极低）**：1.21.1 的 NeoForge 模组 mixin 注入目标包名与 1.20.1 一致（`net/minecraft/...`、`com/mojang/blaze3d/...`），中毒 mixin 五条判据通用，无版本耦合。
- **R3（极低）**：applaunch `-source 7` 约束已在 1.20.1 满足，复制后 1.21.1 同样满足（纯 JDK、无 lambda/diamond/var）。

**回滚**
- 本地改动（未提交）：`git checkout -- bootstrap/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java build.gradle` 即回到 v10。
- 部署回滚：保留旧 `PRTS-1.21.1-1.0.5.jar` 备份，换回即可。
- 隔离区残留：v22 启动会 `migrateLegacyFiles()` 把旧 `_quarantine/` 递归迁移到 `_disabled_mods/`，不丢文件；若想彻底回退，手动移回 mods/ 即可。

## 8. 后续（待授权）

- 部署到 1.21.1 测服（不启动 → 启动验收，按用户节奏）。
- 提交 + 推送 + `gh release v1.0.6`（遵循 git 铁律：仅 `git add` 实际改动 2 文件避 autocrlf 噪声，`--target` 用完整 40 位 SHA）。
- 同步更新 README 守卫说明（配置/路径统一为 guard.yml + _disabled_mods）。
