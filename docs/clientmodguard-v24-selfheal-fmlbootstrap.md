# ClientModGuard v24 — 自愈网补盲：客户端模组在专用服启动期崩溃（FML 早期 init）

> 适用分支：1.21.1（bootstrap, 1.0.6；与 1.20.1 v22 同源，需同步改 1.20.1）
> 关联：v22 真·总开关（1.0.54 / 1.0.6）

## 1. 需求
用户实测（autoQuarantine=true）：客户端模组 **sodium** 在专用服启动期把服崩死，但：
- 预检（自检开启）未隔离 sodium（AMBER 保留，属设计取舍，见 §5）；
- **自愈未启动**，服务端「死在最开始」直接退出，无任何隔离/重启。

目标：让自愈网能覆盖「客户端模组在专用服 FML 早期 init 执行客户端专属代码而崩」这一类崩溃，自动隔离真凶并重启。

## 2. 实证（根因）
崩溃栈（用户日志）：
```
java.lang.reflect.InvocationTargetException
  at ...Method.invoke / Main_Neoforge.main / Launcher.main(Launcher.java:41)
Caused by: java.lang.NoClassDefFoundError: org/lwjgl/Version
  at LAYER SERVICE/sodium_service@0.8.12-beta.2+mc1.21.1/net.caffeinemc.mods.sodium.client.compatibility.checks.PreLaunchChecks.isUsingKnownCompatibleLwjglVersion(...)
  ... (sodium PreLaunchChecks -> SodiumWorkarounds.bootstrap -> fml ImmediateWindowHandler.load)
```
- ⚠️ **更正（见 v24b）**：本分析原断言「`Launcher.main` L42-44 调用了 handleCrash」是**错误的假设**。实际异常在 `Main_Neoforge.main`（src/neoforge）第 23 行 `method.invoke` 内即被 `catch(Exception e){ System.exit(-1); }` 吞掉并退出 JVM，**根本不冒泡到 `Launcher.main`**，故 `handleCrash` 在启动期崩溃路径**从未被调用**。v24 的 clientOrigin 第三门逻辑写对了、但装在了永远到不了的调用点。修复见 `docs/clientmodguard-v24b-mainneoforge-wire.md`：在 `Main_Neoforge` catch 内 `System.exit` 前反射调用 `ClientModGuard.handleCrash(e, args)`。
- `handleCrash` L497-499 两道门：
  ```java
  boolean direct = isClientClassMissing(t);   // 缺失类须 net.minecraft.client / com.mojang.blaze3d 前缀
  boolean modLoadFail = isModLoadingFailed(t); // 消息须含 "Mod Loading has failed"
  if (!direct && !modLoadFail) return;        // 两门都不中 → 直接放弃自愈
  ```
  - `org/lwjgl/Version` **不是**客户端前缀 → `direct=false`；
  - 消息非 "Mod Loading has failed" → `modLoadFail=false`；
  → **L499 直接 return，自愈从未执行**，崩溃交还 `Launcher.main` 的 `throw t` → JVM 退出。

**结论**：自愈网存在盲点——「客户端模组在专用服启动期执行客户端专属代码→缺失客户端专属类（`org.lwjgl.*` 等）→NoClassDefFoundError」这一类崩溃，缺失类非 `net.minecraft.client` 前缀，故不命中 `direct`，也不命中 `modLoadFail`，自愈静默放过。

## 3. 备选对比
| 方案 | 做法 | 优点 | 缺点 |
|------|------|------|------|
| A. 扩 `CLIENT_CLASS_PREFIX` 把 `org/lwjgl` 当客户端前缀 | 改 `isClientClassMissing` 命中集 | 改动小 | 误伤：任何缺失 `org.lwjgl` 的服务端场景都被当客户端崩溃；且只覆盖 lwjgl，换 `com.mojang` 以外库仍漏 |
| B. **崩溃栈顶归属客户端模组 + 类加载/链接错误**（选定） | 新增触发门：`locateOffendingMod(t)` 栈顶非核心帧所在 jar 若 `hasClient` 且异常为 `LinkageError`/`ClassNotFoundException`，即判客户端模组在专用服运行期崩 → 隔离+重启 | 以「栈顶模组是客户端模组」为判据，与 v14 `hasClient` 闸门同源，零误伤；覆盖所有「客户端模组跑客户端专属代码崩」形态 | 仅类加载/链接错误触发（NPE 等非加载错误不自动隔离，避免误删） |
| C. 把 `pruneHarmlessClientMods` 默认改 true | 预检阶段即隔离纯客户端模组 | 从根消除此类模组进服 | 破坏「精度优先」铁律，可能误删双端模组（如 kubejs 附属），属行为重大变更，需用户显式授权 |

## 4. 选定方案与代码位置
**方案 B**，在 `ClientModGuard.java`：
1. 新增 helper `isClassLoadingError(Throwable)`：沿 cause 链判断是否为 `LinkageError`/`ClassNotFoundException`（覆盖 NoClassDefFoundError / NoSuchMethodError / NoSuchFieldError / ExceptionInInitializerError）。
2. `handleCrash`（L497-499 区域）新增第三触发门：
   ```java
   Path originMod = locateOffendingMod(t);          // 栈顶非核心帧所在模组 jar
   boolean clientOrigin = false;
   if (originMod != null && isClassLoadingError(t)) {
       ScanResult r = null; try { r = scanJarFull(originMod); } catch (Throwable ignored) {}
       clientOrigin = (r != null && r.hasClient);
   }
   if (!direct && !modLoadFail && !clientOrigin) return;
   ```
   并在 offender 解析中新增**路径4**：`if (offenders.isEmpty() && clientOrigin && originMod != null)` → 隔离 `originMod`，理由标注「崩溃栈顶归属客户端模组（专用服启动期执行客户端专属代码，如缺失 org.lwjgl.Version）」。
3. `onUncaughtClientFailure` 对齐：定位到 mod 后，仅当 `hasClient && isClassLoadingError(t)` 才隔离（原逻辑无 `hasClient` 闸门，子线程任意异常都可能误删双端模组；对齐后更安全，对真实客户端崩溃无回归）。

**`locateOffendingMod` 已能正确命中 sodium**：其栈顶非核心帧为 `net.caffeinemc.mods.sodium...PreLaunchChecks`，`findJarContainingClass` 查到 sodium jar，`scanJarFull(...).hasClient=true` → `clientOrigin=true` → 自愈触发，隔离 sodium 并重启。

## 5. 预检「没筛全」说明（非 bug，设计取舍 + 已有开关）
`sodium` 在 `decide()` 落 AMBER（L1054-1087）：纯客户端软信号只 `REPORT` 不隔离（v12 精度优先铁律，避免误删双端模组如 kubejs 附属）。
系统**已提供**显式开关：在 `_clientcheck/guard.yml` 设 `pruneHarmlessClientMods: true`，预检阶段即隔离这些纯客户端模组（日志提示句「如需一并清理，请设 pruneHarmlessClientMods: true」即指此）。
→ 是否开启属用户权衡（精度 vs 召回），不擅自改默认；自愈（方案 B）作为崩溃恢复网兜底。

## 6. 验收
- 编译：1.21.1 `:bootstrap:neoforgeJar`（跳过 Spigot）BUILD SUCCESSFUL，产物 `PRTS-neoforge-1.21.1-1.0.6.jar`。
- jar 内 `ClientModGuard.class` 含新串 `CLIENT_ORIGIN` / `ORIGIN_SELFHEAL` / `isClassLoadingError`（Python 读 class 常量池校验）。
- 真机（测服 D:\mc\PRTS-1.21.1，autoQuarantine=true，含 sodium）：
  - 预期：启动期 sodium 触发 NoClassDefFoundError → 自愈隔离 sodium 到 `_disabled_mods/` → 自动重启 → 服起来（若仍有其他客户端模组崩，依 MAX_RESTART 逐个隔离）。
  - 关模式（autoQuarantine=false）：预期仍只打印「自检已关闭」，不进任何自愈。

## 7. 风险与回滚
- R1：类加载错误 + 客户端模组判定若误命中「客户端模组恰好在栈上但崩因是服务端」→ 误隔离。缓解：`isClassLoadingError` 限定为 LinkageError/ClassNotFoundException 类错误（非任意 RuntimeException），且要求 `hasClient`，与 v14 闸门同源；误命中概率低。
- R2：1.20.1 同源需同步改（v22 的 handleCrash 同结构），否则 1.20.1 有同样盲点。
- 回滚：git checkout 还原 ClientModGuard.java / build.gradle，保留旧 jar 备份于 `_trash_deploy`。
