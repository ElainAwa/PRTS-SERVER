# ClientModGuard v22 — 总开关语义修正：autoQuarantine=false 时整套自检完全关闭

## 1. 需求
用户要求 `autoQuarantine` 是一个**真正的总开关**：
- `false`（默认）：整套客户端模组自检功能**完全关闭**——不扫描、不报告、不隔离、不自愈（崩溃交还 JVM 默认行为）。
- `true`：整套自检**完整启用**（启动预检+隔离+运行时自愈）。
不是"只关闭其中一个模块"。

## 2. 实证（现状问题）
用户反馈："文件删干净了，启动怎么还是自检啊，不是默认关闭吗""预检正常跑，模组也会删"。

根因有两点，v21 只解决了一半：
- **`run()` 内 `scan()` 无条件执行**（L313）：无论开关如何，每次启动都跑预检并打印 `[PRTS] 客户端模组预检...`，造成"关了还自检"的观感。
- **`decide()` 的 `inBlack` 黑名单分支（L1035）无条件返回 `QUARANTINE`，不查 `autoQuarantine`**：8 个种子客户端模组（embeddium/oculus/fancymenu/jecharacters/konkrete/LegendaryTooltips/drippyloadingscreen/servercore）即使开关 false 也会被移到 `_disabled_mods/`，造成"关了却还删模组"。

v21 仅给 `handleCrash`/`shutdownHeal`/`onUncaughtClientFailure` 加了早退，但漏了 `scan()` 入口与 `inBlack` 分支，导致开关对"预检扫描"和"种子隔离"两条路径失效。

## 3. 根因
之前把开关当成"隔离/报告"二态 + "自愈开关"，是**按子模块挂开关**的设计，违背用户对"总开关"的定义。正确做法是在**唯一入口** `scan()` 处用开关门控：入口不进，下游 `decide()`（含 `inBlack` 分支，L818 是其唯一调用点）和自愈早退一起构成"全关"语义。

## 4. 备选对比
| 方案 | 改动 | 评价 |
|------|------|------|
| A. 在 `run()` 门控 `scan()`（选定） | 仅入口包一层 `if (cfg.autoQuarantine)` | 单一控制点，彻底关闭整条链路；下游自愈早退仍保留做双保险；最简洁 |
| B. 逐个给 `decide()` 每个分支加 `&& cfg.autoQuarantine` | 改 4+ 处 | 分散易漏，正是 v21 的坑；且 `scan()` 仍会跑（浪费扫描+打印） |
| C. 拆成两个开关（自检开关+自愈开关） | 新增字段 | 违背"一个总开关"需求，回退 |

选定 **A**。

## 5. 选定方案与代码位置
文件：`arclight-forge/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java`（applaunch `-source 7` 约束：禁 lambda/diamond/var）

改动：
1. **`run()` L312-317**：将整段 `scan()` 调用包进 `if (cfg.autoQuarantine) { ... }`，`else` 打印一行 `[PRTS] 客户端模组自检已关闭 (autoQuarantine=false)，整套预检/隔离/自愈均不启用`，避免用户以为功能残缺。
2. **`decide()` 的 `inBlack` 分支**：**不改**——因为 `scan()` 不进则 `decide()` 永不被调用（L818 唯一调用点），种子模组在 false 时自然不会删。保留其"开关 true 时无条件隔离种子"的既有正确行为。
3. **帮助文本 L266**：措辞由"预检仅报告不隔离"改为"整套客户端模组自检完全不启用，不扫描不隔离"，与总开关语义一致。
4. **`Config.autoQuarantine` 字段注释 L2273**：明确"false=整套关闭，不扫描不隔离不自愈"。
5. **`build.gradle` L5**：版本 `1.0.53` → `1.0.54`。

双保险：`handleCrash`(L491)/`shutdownHeal`(L362)/`onUncaughtClientFailure`(L589) 各自早退保留，确保即便未来新增入口也不会绕过开关。

## 6. 验收
- 编译：`BUILD SUCCESSFUL`（`1.0.54`）。
- 测服 `D:\mc\PRTS` 部署后：
  - `guard.yml` 中 `autoQuarantine: false`：启动日志**不应出现**任何 `[PRTS] 客户端模组预检` 行，且 `_disabled_mods/` 不被改动、种子模组留在 `mods/`。
  - 改 `autoQuarantine: true` 重启：预检+隔离+自愈全套生效，种子模组被隔离，与 v21 行为一致。
- 校验 jar 内部 `ClientModGuard` 字节含新门控（javap 看 `run` 方法含 `autoQuarantine` 字段读取）。

## 7. 风险与回滚
- 风险：极低。`false` 路径仅新增一个不透明分支 + 一行提示，无副作用；`true` 路径逻辑与 v21 完全一致。
- 回滚：`git checkout` 改动文件 + `build.gradle` 回 `1.0.53`，重新构建部署。
- 注意：默认 `false` 下若用户期望"自动隔离崩服客户端模组"，必须显式设 `autoQuarantine: true`，否则整功能不工作（符合本需求定义）。
