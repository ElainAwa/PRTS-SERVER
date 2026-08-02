# v26 — createimp 双端模组服务端兼容：客户端引用净化

## 1. 需求

`Create Improve (createimp-1.3.0)` 是**双端模组**，服务端必须加载它（含服务端玩法逻辑）。

用户明确要求两点：

1. **客户端预检不得处理它** —— 不隔离、不移动。
2. **解决兼容性问题** —— 让它能在专用服务端正常加载运行。

## 2. 实证

### 2.1 崩溃现象

`crash-2026-08-01_22.58.23-fml.txt` / `22.58.59-fml.txt`，连续两次同一原因：

```
Failure message: Create Improve (createimp) encountered an error while dispatching
                 the net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event
java.lang.RuntimeException: Attempted to load class net/minecraft/client/multiplayer/ClientLevel
                            for invalid dist DEDICATED_SERVER
    at net.neoforged.fml.common.asm.RuntimeDistCleaner.processClassWithFlags(RuntimeDistCleaner.java:60)
    ...
    at com.molox.createimp.CreateImp.registerPayloads(CreateImp.java:216)
```

### 2.2 字节码定位（javap 实证）

`CreateImp.class` 的 `LineNumberTable` 给出 `line 216 -> offset 295`：

```
295: aload_1
296: getstatic     #309  // WorkWarehouseActivateEffectPacket.TYPE
299: getstatic     #312  // WorkWarehouseActivateEffectPacket.STREAM_CODEC
302: invokedynamic #313  // IPayloadHandler
307: invokevirtual #226  // PayloadRegistrar.playToClient
```

`BootstrapMethods #24` 目标不是 lambda，而是 packet 类自身的静态方法：

```
REF_invokeStatic com/molox/createimp/network/WorkWarehouseActivateEffectPacket.handle
```

也就是说：**第 216 行触发的是「加载 `WorkWarehouseActivateEffectPacket` 这个类」**。

### 2.3 全量扫描：3 个雷，不是 1 个

对 `com/molox/createimp/network/` 下 31 个类做常量池扫描，命中客户端引用的有 3 个，**全部是 playToClient**：

| Packet 类 | 引用的客户端类 |
|---|---|
| `WorkWarehouseActivateEffectPacket` | `Minecraft`, `ClientLevel` ← 当前崩的 |
| `WorkWarehouseMaterialsReadyEffectPacket` | `Minecraft`, `ClientLevel` |
| `OpenTemplateMaterialsGuiPacket` | `Minecraft`, `Screen` |

修一个会立刻冒出下一个，必须一次全修。

### 2.4 引用位置：全在合成 lambda 内

`javap -c` 精确定位，三个类模式完全一致：

```
METHOD: private static void lambda$handle$2(WorkWarehouseActivateEffectPacket);
   0: invokestatic  // Minecraft.getInstance()
   3: getfield      // Minecraft.level : ClientLevel
```

对应源码形态：

```java
public static void handle(Packet p, IPayloadContext ctx) {
    ctx.enqueueWork(() -> {           // ← lambda$handle$N，客户端逻辑全在这里
        Minecraft.getInstance().level ...
    });
}
```

**`handle` 主体本身是干净的**，客户端引用 100% 在 `lambda$handle$N` 合成方法里。

## 3. 根因

三层因果链：

1. **模组侧缺陷**：createimp 把客户端专属逻辑直接写在双端 packet 类的 lambda 中，未做 dist 隔离（未拆分 client-only 类、未走 `DistExecutor` 之类的隔离手段）。
2. **JVM 类验证是整类级的**：加载 `WorkWarehouseActivateEffectPacket` 时，验证器要对**所有方法**（含未被调用的 `lambda$handle$2`）做类型可赋值性检查，因此必须解析 `ClientLevel`。
3. **FML 拒绝加载**：`RuntimeDistCleaner` 在 `DEDICATED_SERVER` 下对 `net.minecraft.client.**` 一律抛 `RuntimeException`。

> 关键推论：**`@Overwrite handle` 无效**。即使把 `handle` 换成空方法，`lambda$handle$2` 作为死方法仍会被验证器检查，照样加载 `ClientLevel`。**必须净化 lambda 本身。**

## 4. 备选方案对比

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A. 隔离 createimp | 守卫移入 `_disabled_mods` | 零成本 | **违背用户要求**，双端模组服务端玩法丢失 | ❌ 否决 |
| B. `@Overwrite handle` | Mixin 替换 handle 方法体 | 思路直接 | **原理上无效**（见 §3 推论），lambda 仍被验证 | ❌ 否决 |
| C. 跳过 registerPayloads | Mixin 取消整个方法 | 实现简单 | payload channel 全部未注册，客户端连服协商失败被踢 | ❌ 否决 |
| D. 放行 RuntimeDistCleaner | Mixin 让其对客户端类不抛异常 | 一劳永逸 | `ClientLevel` 真被加载 → 连锁加载 blaze3d/lwjgl → 雪崩 | ❌ 否决 |
| E. 直接改模组 jar | ASM 净化后重打包 createimp | 零核心风险、立即见效 | 模组一更新就失效；换服要重做；不可复用 | ⭕ 备选 |
| **F. 核心侧字节码净化器** | 新增 `Implementer` 走 ASM 清空客户端 lambda | 通用、可配置、随核心分发、复用于后续同类模组 | 需改核心，要防误伤 | ✅ **选定** |

## 5. 选定方案（F）

### 5.1 落点

PRTS 已有可插拔的 ASM 体系，天然契合，**零架构侵入**：

- 接口：`bootstrap/src/main/java/io/izzel/arclight/boot/asm/Implementer.java`
  仅一个方法 `boolean processClass(ClassNode node)`。
- 注册处：`bootstrap/src/neoforge/java/io/izzel/arclight/boot/neoforge/mod/ArclightImplementer.java`
  `initializeLaunch()` 内 `implementers.put("...", ...)`。
- 模板：`EnumDefinalizer.java`（硬编码类名集合 + ASM tree 改写）。

### 5.2 新增文件

`bootstrap/src/main/java/io/izzel/arclight/boot/asm/ClientRefSanitizer.java`

### 5.3 三重安全门（防误伤，缺一不可）

误伤的最坏情况是清空了含服务端逻辑的方法（例如 `if (level.isClientSide) {客户端} else {服务端}` 被整体清空）。因此**只有同时满足三条**才净化：

1. **类名在名单内** —— 只处理显式配置的类，其余类直接跳过（同时保证性能，不做全量指令扫描）。
2. **方法是合成客户端回调** —— 方法名以 `lambda$` 开头（或名单显式指定的方法名）。这类方法是 `enqueueWork(() -> ...)` 的客户端回调，服务端永不执行。
3. **方法体确实引用客户端类** —— 扫描 `MethodInsnNode` / `FieldInsnNode` / `TypeInsnNode` / `LdcInsnNode` 的 owner 与 desc，命中 `net/minecraft/client/**` 或 `com/mojang/blaze3d/**`。

### 5.4 净化动作

**清空方法体，而非删除方法**（删除会导致 BootstrapMethods 的 MethodHandle 解析失败 → `NoSuchMethodError`）：

- `instructions.clear()`，按返回类型追加对应 return 指令（此处均为 `void` → `RETURN`）
- 清空 `tryCatchBlocks`、`localVariables`
- 重置 `maxStack` / `maxLocals`

### 5.5 内置名单（本次三条）

```
com/molox/createimp/network/WorkWarehouseActivateEffectPacket
com/molox/createimp/network/WorkWarehouseMaterialsReadyEffectPacket
com/molox/createimp/network/OpenTemplateMaterialsGuiPacket
```

### 5.6 运行时正确性论证

三个包全部是 **playToClient**（服务端 → 客户端）：

- 服务端只**发送**，发送路径不触及 `handle`；
- 服务端**永不接收**，`handle` 及其 lambda 在服务端本就是死代码；
- `TYPE` / `STREAM_CODEC` / record 构造器 / 编解码 lambda 全部保留，注册与序列化不受影响；
- payload channel 正常注册，**客户端连服协商不受影响**；
- 玩家客户端加载的是原版 createimp jar，客户端功能零损失。

### 5.7 预检豁免（用户要求 1）

`D:\mc\PRTS-1.21.1\_clientcheck\guard.yml` 的 `allowlist` 追加两条（**已执行**）：

```yaml
  - createimp-1.3.0.jar
  - createimp
```

> 注意 v25b 已查明的匹配规则：守卫比对的 `fileName` 是**含 `.jar` 扩展名的小写全名**，因此必须写全名；额外加 modId 作双保险。

## 6. 验收标准

1. 服务端启动不再出现 `Attempted to load class net/minecraft/client/... for invalid dist DEDICATED_SERVER`。
2. 日志出现净化记录（Implementer 层 debug）。
3. `createimp` 保持在 `mods/`，不被移入 `_disabled_mods`。
4. 服务端跑到 `Done (Xs)!`。
5. 客户端玩家可正常连入，Create Improve 的方块/GUI 功能正常。

## 7. 风险与回滚

| 风险 | 评估 | 缓解 |
|---|---|---|
| 误伤服务端逻辑 | 极低 | 三重门限定；名单外的类完全不碰 |
| `NoSuchMethodError` | 无 | 只清空方法体，不删方法签名 |
| 性能开销 | 可忽略 | 第一重门是类名 `Set.contains`，名单外立即返回 |
| createimp 升级后类名变化 | 中 | 名单失配即退化为「不处理」，回到当前崩溃状态，不会引入新故障 |

**回滚**：注释掉 `ArclightImplementer.initializeLaunch()` 中的一行 `implementers.put(...)` 即可完全停用，无残留副作用。

## 8. 版本号

本次为**本地迭代**，版本号保持 `1.0.6` 不变。仅在用户授权发版时才升版号。
