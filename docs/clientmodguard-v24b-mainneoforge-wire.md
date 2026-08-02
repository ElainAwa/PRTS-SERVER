# ClientModGuard v24b — 启动期崩溃自愈接线修复（1.21.1）

> 适用分支：1.21.1（bootstrap，本地迭代态 1.0.6；与 1.20.1 同源，后续需同步）
> 前置：v24（handleCrash 第三触发门 clientOrigin / isClassLoadingError）逻辑已写入 ClientModGuard.java，但**实测自愈仍不触发**。

## 1. 需求
1.21.1 测服开启 `autoQuarantine=true` 后：预检正常（隔离 7 个纯客户端模组），但 sodium 在启动期 `ImmediateWindowHandler` 跑 `PreLaunchChecks` 缺 `org.lwjgl.Version` 崩服（`NoClassDefFoundError`），**自愈未启动**，只打印 `Fail to launch Arclight.` 后退出。

## 2. 实证（用户日志）
- 预检段完整执行并隔离 7 个 → `autoQuarantine` 确为 true，`run()`/`scan()` 路径正常。
- 崩溃栈顶：
  ```
  Caused by: java.lang.NoClassDefFoundError: org/lwjgl/Version
      at LAYER SERVICE/sodium_service@.../net.caffeinemc.mods.sodium...PreLaunchChecks.isUsingKnownCompatibleLwjglVersion(...)
  ```
- 控制台**无任何 `[PRTS] 自愈/隔离` 字样**，只有 `Fail to launch Arclight.`。

## 3. 根因
调用链被上游吞掉，v24 的 `handleCrash` 改进**从未被调用**：

`Launcher.main`（arclight）第 41 行 `main.invoke(args)` 反射调用 `Main_Neoforge.main`；`Main_Neoforge.main` 第 23 行 `method.invoke(null, target)` 反射调用 Forge/NeoForge 实际 `main`。sodium 崩在 `method.invoke` 内，被 `Main_Neoforge.main` 第 24-28 行的 `catch (Exception e)` 捕获：

```java
} catch (Exception e) {
    e.printStackTrace();
    System.err.println("Fail to launch Arclight.");
    System.exit(-1);   // ← 直接退出 JVM，异常根本不冒泡到 Launcher.main:44 的 handleCrash
}
```

v24 文档「Launcher.main L42-44 确实调用了 handleCrash」是**错误假设**——它假设异常会冒泡到 Launcher.main，实际在 Main_Neoforge 即被吞并 `System.exit`。

## 4. 备选对比
- **A. 在 Main_Neoforge 接 handleCrash（选定）**：在 `System.exit(-1)` 前调用 `ClientModGuard.handleCrash(e, args)`；自愈成功则内部 `restart()` 退出当前 JVM，不命中则照常 Fail to launch。`e` 即 `InvocationTargetException`，与 Launcher.main 传入形态一致，handleCrash 的 cause 链遍历/第三门 clientOrigin 直接复用，零重复逻辑。
- **B. 让 Main_Neoforge 重抛异常**：改为 `throw e` 让 Launcher.main catch 接管。但 `Launcher.main` catch 在 handleCrash 之后仍是 `throw t`，会先打印 `Fail to launch` 再自愈，控制台噪声大，且 `System.exit(-1)` 兜底语义需重写。不如 A 干净。
- **C. 在 Main_Neoforge 内联自愈**：逻辑重复、与 1.20.1 双分支不一致、后续难维护。否决。

## 5. 选定方案与代码位置
- **文件**：`bootstrap/src/neoforge/java/io/izzel/arclight/boot/neoforge/application/Main_Neoforge.java`
- **改动**：新增 `import io.izzel.arclight.server.ClientModGuard;`，在 catch 块 `System.exit(-1)` 之前插入 `try { ClientModGuard.handleCrash(e, args); } catch (Throwable ignored) {}`。
- **不改动**：ClientModGuard.java（v24 的 clientOrigin 第三门保留，现在终于被走到）；Launcher.java（保持原 fallback 接线，非 Main_Neoforge 路径仍生效）。
- **铁律**：源码注释 ≤ 2 行；版本号保持 1.0.6（本地迭代不升版号，发版时才升）。

## 6. 验收
- 构建 `:bootstrap:neoforgeJar`（临时 `-Darclight.skipSpigot=true`，验证后还原补丁）。
- jar 内 `Main_Neoforge.class` 含 `ClientModGuard` 常量引用（Python 读 class 常量池校验）。
- 测服开 `autoQuarantine=true`，放入 sodium 复测：应见 `[PRTS] 自愈完成（隔离 1 个模组），自动重启服务端...` 后自动二次启动，sodium 进入 `_disabled_mods`，服务起来。

## 7. 风险与回滚
- 风险1：handleCrash 内部 `restart()` 重启命令重建失败 → 退化为 `Fail to launch`（与现状一致），不会更差。
- 风险2：非客户端崩溃被误判命中 → v24 已加 `hasClient`+`isClassLoadingError` 双闸门守零误删，且 handleCrash 不命中则 `return`，仍走 `System.exit(-1)`。
- 回滚：移除 Main_Neoforge 两行接线即可回到原行为。
