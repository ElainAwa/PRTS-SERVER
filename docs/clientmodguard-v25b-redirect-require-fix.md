# v25b — Beehive @Redirect 冲突崩服真修：require=0 而非 expect=0

## 1. 需求

v25 已针对 `BeehiveBlockEntityMixin` 与 `neobeefix` 的 `@Redirect` 冲突加了 `expect = 0`，
并完成构建 + 部署（含删 `mod_file` 强制重提取）。但用户复测**崩溃一模一样**。
需要查清 v25 修复为何无效，并给出真正能生效的修法。

## 2. 实证（本轮取证，非推测）

### 2.1 修复确实进了全链路 jar

用 Python 解析常量池，逐层校验 `BeehiveBlockEntityMixin.class`：

| 层 | 路径 | mtime | 常量 `expect` |
|---|---|---|---|
| 构建产物 | `bootstrap/build/libs/PRTS-neoforge-1.21.1-1.0.6.jar` → 内嵌 `/common.jar` | 22:19:16 | **True** |
| 部署根 jar | `D:\mc\PRTS-1.21.1\PRTS-neoforge-1.21.1-1.0.6.jar` → 内嵌 `/common.jar` | 22:26:46 | **True** |
| 运行时提取 | `.arclight/mod_file/PRTS-1.21.1-1.0.6-ff3adcf.jar` | **22:31:47** | **True** |

结论：**不是部署没生效**。`mod_file` 于 22:31:47 重新提取，正是崩溃那次启动。

### 2.2 崩溃那次启动确实加载了新代码

`logs/latest.log`：启动横幅 `22:31:47.089` → 崩溃 `22:31:57.427`，
与 `mod_file` 提取时间 `22:31:47` 同一次启动。带 `expect=0` 的新代码**已被加载，仍然崩**。

### 2.3 崩溃原文

```
[22:31:57.418] [mixin/WARN] @Redirect conflict. Skipping
  mixins.arclight.core.json:...BeehiveBlockEntityMixin ->arclight$bypassNightCheck priority 500,
  already redirected by neobeefix.mixins.json:BeehiveBlockEntityMixin
  ->noSkyLight_isNotNight priority 1000

[22:31:57.427] InjectionError: Critical injection failure: Redirector
  arclight$bypassNightCheck ... failed injection check, (0/1) succeeded. Scanned 0 target(s).
```

### 2.4 分母 `1` 的来源

`arclight-common/src/main/resources/mixins.arclight.core.json`：

```json
"injectors": {
  "maxShiftBy": 2,
  "defaultRequire": 1
}
```

`BeehiveBlockEntityMixin` 属于 `io.izzel.arclight.common.mixin.core` 包，正由该 config 管辖。

## 3. 根因

Mixin 的 `InjectionInfo.postInject()` 有两条独立分支：

- **`require`** 不满足 → `throw InjectionError("Critical injection failure ... (n/require) succeeded")`（**致命，崩服**）
- **`expect`** 不满足 → 仅打 `WARN`（不致命）

而 `@Redirect.require()` 默认值是 `-1`，含义是「未显式指定」，此时**回退到 mixin config 的 `defaultRequire`**，
本项目该值为 `1`。

于是实际生效的是 `require = 1`：被 neobeefix（priority 1000）抢走注入点后成功数为 0，`0 < 1` → 抛致命异常。

**v25 只改了 `expect`，动的是那条永远只打 WARN 的分支，对致命分支毫无影响。**
日志里 `(0/1)` 的分母 `1` 就是 `require`，不是 `expect` —— 这是当初误判的关键线索。

## 4. 备选方案对比

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（选定）** | `@Redirect(..., expect = 0, require = 0)` | 1 行改动，精确到单个注入点。冲突时静默让出，无冲突时照常生效。`expect=0` 一并消除 WARN 噪声 |
| B | 提高本 mixin 优先级到 > 1000 | 反向压制 neobeefix；若 neobeefix 自身 `require=1` 则改成它崩，且属于抢夺第三方模组行为 |
| C | 改 `mixins.arclight.core.json` 的 `defaultRequire: 1` → `0` | 影响面波及 core 包**全部** mixin，会掩盖真实的注入失效（本该崩的静默失败），风险过大 |
| D | 移除该 `@Redirect` | 丢掉 Bukkit 的蜜蜂夜间/雨天离巢兼容语义，属功能倒退 |

选 A：改动最小、影响面锁定单点、语义正确（「这个 redirect 允许被更高优先级接管」）。

功能影响：冲突时夜间检查绕过逻辑让位给 neobeefix。这是可接受的 —— neobeefix 本就是专门修蜜蜂行为的模组，
两者语义重叠，且用户已确认保留 neobeefix。

## 5. 代码位置

`arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/world/level/block/entity/BeehiveBlockEntityMixin.java:98`

```java
// 改前
@Redirect(method = "releaseOccupant", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isNight()Z"), expect = 0)

// 改后
@Redirect(method = "releaseOccupant", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isNight()Z"), expect = 0, require = 0)
```

## 6. 附带修复：create_hypertube 白名单匹配失效

### 实证

`ClientModGuard.java:998` 构造用于比对的键：

```java
String fileName = jar.getFileName().toString().toLowerCase();   // 含 .jar 扩展名
```

`ClientModGuard.java:1010` 比对：

```java
if (cfg.whitelist.contains(fileName)) { ... }
```

v25 写入 `guard.yml` 的条目是 `create_hypertube-0.5.0-alpha-neoforge`（**无 `.jar`**）→ `contains` 恒为 false
→ 白名单等于没写，即便手工移回 `mods/`，下次预检仍会重新隔离。

### 修法（仅改配置，不动代码）

`D:\mc\PRTS-1.21.1\_clientcheck\guard.yml`：

```yaml
allowlist:
  - create_hypertube-0.5.0-alpha-neoforge.jar   # 全文件名，命中 fileName 比对
  - create_hypertube                            # modId，命中 modId 比对（双保险）
```

并把 jar 从 `_disabled_mods` 移回 `mods/`（已执行）。

## 7. 验收标准

1. 构建产物内嵌 `/common.jar` 的 `BeehiveBlockEntityMixin.class` 常量池同时含 `expect` 与 `require`
2. 部署后删 `.arclight/mod_file/*`，重启触发重提取
3. 启动日志仍可见 `@Redirect conflict ... Skipping`（WARN，正常，表示让出注入点）
4. **不再出现** `Critical injection failure` / `MixinTransformerError`，服务端跑到 `Done (Xs)`
5. `create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 保持在 `mods/`，预检日志不再将其隔离

## 8. 风险与回滚

- 风险极低：`require=0` 仅放宽单个注入点的强制校验，不改变任何运行时逻辑。
- 若 neobeefix 日后移除，本 redirect 会重新正常注入（`require=0` 不阻止成功注入）。
- 回滚：删除 `require = 0`（还原为 v25 状态）即可，1 行。
- 白名单回滚：删 `guard.yml` 中 allowlist 两行条目。

## 9. 版本

本地迭代，**版号保持 1.0.6 不升**（遵循「本地迭代不升版号」铁律）。
如后续用户授权发版，再升 1.0.7 并一并提交 `BeehiveBlockEntityMixin.java`。
