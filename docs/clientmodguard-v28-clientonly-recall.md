# ClientModGuard v28：纯客户端模组召回率修复（NeoForge）

## 一、需求

v27（1.0.7）上线后实测：`mods/` 189 个 jar 中只判出 **12 个**纯客户端模组，而人工扫描报告 `clientmod-scan-1.21.1.md` 列出 17 个，两者仅重合 9 个。同时 sodium 未被预检拦下，直到启动崩溃才由运行期自愈隔离。

目标：把"不崩服但纯客户端"的模组在**预检阶段**准确识别出来，不依赖崩溃触发。

## 二、实证

脚本 `_scan/diag27.py` / `diag27b.py` / `diag27c.py`，对象 `mods/`(189) + `_disabled_mods/`(10)。

### 2.1 v27 线上判定与人工报告的差集

| 分类 | 数量 | 清单 |
|---|---|---|
| 线上 v27 命中 | 12 | controlling, entityculling, extrasounds, fusion, irisflw, jeed, mousetweaks, notenoughanimations, pingwheel, presencefootsteps, yeetusexperimentus, yacl |
| 报告 17 中被 v27 漏掉 | 8 | visual_keybinder, probejs, advancementframes, jadeaddons, ponderer, connectiblechains, oelib, searchables |
| v27 有而报告没有 | 3 | extrasounds, irisflw, presencefootsteps |

### 2.2 逐个剖包结论（diag27b.py）

| 模组 | 根级 mixin 配置实况 | 判定 |
|---|---|---|
| probejs | `probejs.mixins.json` {client:4, mixins:0} | 应命中，v27 却漏 |
| oelib | `oelib-neoforge.mixins.json`{client:1} + `oelib.mixins.json`{全空} | 空配置否决了判定 |
| searchables | `searchables.mixins.json`{client:1} + `.neoforge.`{全空} | 同上 |
| connectiblechains | `connectiblechains.mixins.json`{client:1} + `.neoforge.`{全空} | 同上 |
| sodium | 根级 **0 个** json，真身在 `META-INF/jarjar/*-mod.jar` | 外层无证据，B 类信号天然失效 |
| advancementframes | 两个 json 三段**全空**，且含 `data/*/recipes` | **人工报告误判**，实为双端 |
| jadeaddons | `jadeaddons.mixins.json` {mixins:1, client:2} | **人工报告误判**，有通用段 |

### 2.3 未被使用的硬信号：`mods.toml` 依赖 side

sodium 的 `neoforge.mods.toml` 中对 `minecraft` / `neoforge` 的 **required 依赖全部 `side = "CLIENT"`**。全量统计该信号命中 5 个：controlling、iris、lanserverproperties、sodium、visual_keybinder —— **全部为真客户端模组，零误伤**。这是 NeoForge 下唯一的声明式硬证据。

### 2.4 修正判据的全量验证（diag27c.py）

判据 = `A(依赖 side 全 CLIENT)` **OR** `B(根级 mixin 仅 client 段，跳过空配置，不递归 JiJ)`

```
mods/ 命中 18（v27 为 12）
NEW  : connectiblechains, oelib, ponderer, probejs, searchables, visual_keybinder
LOST : 无
```

与人工报告 17 的关系：命中其中 15 个；`advancementframes` / `jadeaddons` 经剖包确认为报告误判，不应命中；额外正确捕获 `extrasounds` / `irisflw` / `presencefootsteps` 3 个报告漏项。

> 注：JiJ 递归**不能**用于本判定。sodium 内嵌的 fabric-api 带通用 `mixins` 段，递归后反而把 sodium 判成非纯客户端。

## 三、根因

三处独立缺陷，位于 `bootstrap/src/applaunch/java/io/izzel/arclight/server/ClientModGuard.java`：

1. **L1445 early-break 截断扫描**
   `if (r.hasClient && r.hasServer && r.hasContent) break;`
   三个信号先于 mixin 配置置位时直接跳出条目遍历，根级 `*.mixins.json` 根本没被读到 → `anyMixinCfg=false`。这是 probejs 等模组漏判的主因。

2. **L1439-1442 空 mixin 配置否决判定**
   三段全空的配置（多见于 multi-loader 打包的占位 json）走到 `if (!cfgClientOnly) allClientOnly = false;`，一票否决同 jar 内其它"仅 client 段"的真实配置。

3. **缺少依赖 side 信号**
   外层为壳、真身在 JiJ 的模组（sodium/iris）根级无 mixin 配置，B 类信号无从下手，而现成的 `[[dependencies]] side` 硬证据未被解析。

## 四、备选方案对比

| 方案 | 做法 | 取舍 |
|---|---|---|
| 甲 | 直接删除 early-break | 实现最简，但每个 jar 都要读完全部 class 字节，189 jar 预检耗时显著上升 |
| 乙 | 递归 JiJ 收集 mixin 配置 | 反效果：sodium 内嵌 fabric-api 通用段污染判定，实测把 sodium 判成非客户端 |
| **丙（选定）** | 独立轻量预扫 + 空配置跳过 + 新增依赖 side 信号 | 只读 `mods.toml` 与根级 json（体量极小），不触碰 early-break 的性能优化；三处缺陷全覆盖 |

## 五、选定方案与代码位置

1. **新增轻量预扫方法** `prescanSideSignals(JarFile jf, ScanResult r)`，在 `scanJarFull` 主循环**之前**调用（L1404 `try (JarFile jf = ...)` 之后）：
   - 解析 `META-INF/neoforge.mods.toml` / `mods.toml` 的 `[[dependencies.*]]` 块，对 `modId ∈ {minecraft, neoforge, forge}` 且 required 的项收集 `side`；全为 `CLIENT` → `r.declaredClientSide = true`（信号 A）。
   - 遍历根级 `*.json` 且文件名含 `mixin`，按信号 B 规则累计 `anyMixinCfg` / `allClientOnly` / `anyServerOrCommon`，**三段全空的配置直接 `continue`**（修复根因 2）。

2. **主循环 L1428-1443 的 mixin 解析块删除**，其职责已移入预扫，避免重复读取；`L1445` early-break 保持不变（修复根因 1 —— 判定不再依赖主循环是否跑完）。

3. **`ScanResult` 新增字段** `boolean declaredClientSide;`（L1886 附近，与 `clientOnlyMixin` 并列）。

4. **L1460 终判改为**
   `r.clientOnlyMixin = r.declaredClientSide || (anyMixinCfg && allClientOnly);`

5. **Goal B 分级安全阀**（L1051 `goalBMove`）：追加 `&& !r.hasContent`。
   `connectiblechains` / `jeed` 虽 mixin 仅 client 段，但携带 `data/*/recipes|tags`，属服务端有内容的模组，只报告不隔离。

6. **`GuardState` 缓存**（L2560 正则、L2621 序列化）同步 `declaredClientSide` 字段；缓存格式变更需一并更新正则分组序号。

7. **`sig()` 日志**（L1263）追加 `declSide=` 位，便于逐 jar 复核。

## 六、验收

1. 构建 1.0.8 部署测服，`guard.yml` 保持 `quarantineClientOnly: false`。
2. `_clientcheck/precheck.log` 的 `DONE` 行 `clientOnly=` 应为 **18**（当前 12）。
3. `clientOnly=1` 清单须包含 6 个新增项：connectiblechains、oelib、ponderer、probejs、searchables、visual_keybinder；且原 12 项一个不少。
4. `advancementframes` / `jadeaddons` 须保持 `clientOnly=0`（验证不回归为误报）。
5. 把 sodium 放回 `mods/` 重启一次：预检阶段即应打出 `clientOnly=1 declSide=1`，不再依赖崩溃自愈。
6. 服务端正常 Done，无新增 `Missing dependency`。

## 七、风险与回滚

- **风险 1**：信号 A 依赖模组作者正确书写 `side`。写错（把双端模组标 CLIENT）会误判。缓解：Goal B 默认关闭，仅报告；且 `BUILTIN_SAFE` / `trustedModList` / `allowlist` 三层否决仍在最前。
- **风险 2**：`ponderer` 被新判为纯客户端。它 JiJ 内含 flywheel/ponder 通用段，但外层自身 mixin 仅 client。若后续实测服务端需要它，加入 `allowlist` 即可。
- **风险 3**：缓存正则分组序号改动易错。构建后须删 `_clientcheck/state.json` 重扫一次，确认无 `STATE_PARSE_FAIL`。
- **回滚**：还原 `ClientModGuard.java`，`build.gradle` 版本退回 1.0.7，重新部署 1.0.7 jar 并清 `.arclight/mod_file/` 缓存。
