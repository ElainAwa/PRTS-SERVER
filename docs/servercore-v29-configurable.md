# ServerCore 优化可配置化（v29 / 1.0.9）

## 一、需求

1. **修复蜜蜂 NPE 崩服**：两份崩溃报告（`crash-2026-08-02_01.54.01` / `_01.59.35`）均为同一根因——`Bee.isHiveValid` tick 时 `ChunkManager.hasChunk` 收到 `null` 的 `hivePos` 触发 `NullPointerException`，直接崩服。原版（1.0.6/1.0.7/1.0.8 同款 bug）。
2. **把移植进核心的 ServerCore 优化做成可配置**：用户希望像原版 ServerCore 模组那样用 `config.yml` 逐项开关，出问题能临时关掉某一项而不必改代码重编。

## 二、实证（两份崩溃报告）

| 项 | 报告1 | 报告2 |
|---|---|---|
| Description | Ticking entity | Ticking entity |
| 异常 | `NullPointerException: pos is null` → `pos.getX()` | 完全相同 |
| 第一帧 | `ChunkManager.hasChunk(ChunkManager.java:54)` | 完全相同 |
| 触发链 | `Bee.isHiveValid` ← `Bee.aiStep` ← 蜜蜂 tick | 完全相同 |
| 中招实体 | `productivebees:reed_bee` | `productivebees:green_carpenter_bee` |
| 核心版本 | `PRTS-1.21.1-1.0.6-ff3adcf.jar` | 完全相同 |

无嵌套 `Caused by`，单一 NPE，两个不同蜜蜂实体同一崩点 → 某类实体共性缺陷。

## 三、根因

`arclight-common` 移植的 ServerCore 蜜蜂优化 `BeeMixin.servercore$onlyValidateIfLoaded`：

```java
@Inject(method = "isHiveValid", at = @At("HEAD"), cancellable = true)   // 1.21.1 版
private void servercore$onlyValidateIfLoaded(CallbackInfoReturnable<Boolean> cir) {
    if (!ChunkManager.hasChunk(this.level(), this.hivePos)) {           // ← hivePos @Nullable，可能为 null
        cir.setReturnValue(false);
    }
}
```

`hivePos` 是 `@Nullable` 字段。当蜜蜂尚未分配蜂巢（productivebees 独居蜂 / 蜂巢被移除），`hivePos == null`，注入点在 HEAD **早于**原版 `isHiveValid` 的 null 判据执行，把 null 直接传入：

```java
// ChunkManager.java:53-55
public static boolean hasChunk(Level level, BlockPos pos) {
    return hasChunk(level, pos.getX() >> 4, pos.getZ() >> 4);   // pos.getX() → NPE
}
```

> 1.20.1 树 `BeeMixin` 用 `@Redirect` 挂在 `isTooFarAway(pos)` 上，该调用点在原版 null 判据**之后**，被原版挡住不崩。仅 1.21.1 的 HEAD 注入裸奔中招。

## 四、备选方案

| 方案 | 内容 | 优点 | 缺点 |
|---|---|---|---|
| A（采用） | `ChunkManager.hasChunk(Level,BlockPos)` 加 `if(pos==null) return false;` 常驻守卫 | 一行、改真正崩点、对所有调用方（GroundPathNavigation/areChunksLoadedForMove 等）免疫 | 无 |
| B | `BeeMixin` 注入点加 `if(this.hivePos!=null && ...)` | 更贴近 ServerCore 原意 | 只护蜜蜂一处方，其它 null 路径不免疫 |

另：为彻底解决"优化翻车无退路"，把 4 组 ServerCore 优化全部挂到 `config/servercore.yml` 的开关下，用户可逐项关。

## 五、选定方案与代码位置

### 5.1 NPE 修复（Fix A，常驻，不依赖配置）
- 文件：`arclight-common/.../optimization/general/servercore/ChunkManager.java:53`
- 改动：`hasChunk(Level, BlockPos)` 入口 `if (pos == null) return false;`
- 语义：null 位置 = 无蜂巢 = 视为未加载 → `isHiveValid` 返回 false（与原版一致）。
- **始终生效**（防御性，零成本），即使该优化被配置关闭也无碍。

### 5.2 新增配置门控（ServerCoreConfig）
- 新建：`arclight-common/.../optimization/general/servercore/ServerCoreConfig.java`
- 配置路径：`config/servercore.yml`（专用服务器 CWD=服务端根目录，`new File("config","servercore.yml")`）
- 依赖：已声明 `arclight-common/build.gradle:38 implementation libs.snakeyaml`，直接用 `org.yaml.snakeyaml.Yaml`
- 首次运行若不存在则自动写出带注释的默认文件（默认全 `true`，即当前行为）
- API：`ServerCoreConfig.isEnabled(Feature)` —— `Feature.SYNC_LOADS / CHUNK_TICKETS / BIOME_LOOKUPS / PATHFINDING`；总开关 `enabled:`，任一为 false 则该组失效
- 加载：懒加载 + 双重检查锁，首次 mixin 调用时读；之后仅 HashMap 读（每 tick 调用的开销可忽略）

### 5.3 门控 11 个 mixin（关闭时返回原值/不取消，行为回退原版）

| 组 Feature | 文件 | 注入 | 关闭时 |
|---|---|---|---|
| SYNC_LOADS | `sync_loads/BeeMixin` | @Inject HEAD cancellable | `return;`（不 setReturnValue，跑原版） |
| SYNC_LOADS | `sync_loads/GroundPathNavigationMixin` | @Inject HEAD cancellable | `return;` |
| SYNC_LOADS | `sync_loads/MapItemMixin` | @Redirect x2 | 返回原 `level.getChunk(...)` / `chunk.isEmpty()` |
| SYNC_LOADS | `sync_loads/RemoveBlockGoalMixin` | @Redirect | 返回原 `level.getChunk(x,z,status,create)` |
| SYNC_LOADS | `sync_loads/DynamicGameEventListenerMixin` | @Redirect | 返回原 `level.getChunk(x,z,status,bl)` |
| SYNC_LOADS | `sync_loads/ServerLevelMixin` | @Override clip | `return super.clip(context);` |
| SYNC_LOADS | `sync_loads/StructureCheckMixin` | @Inject cancellable | `return;` |
| CHUNK_TICKETS | `tickets/ChunkGeneratorMixin` | @Redirect | 返回原 `structureManager.getAllStructuresAt(pos)` |
| CHUNK_TICKETS | `tickets/NaturalSpawnerMixin` | @Redirect | 返回原 `level.getBlockState(pos)` |
| BIOME_LOOKUPS | `biome_lookups/NaturalSpawnerMixin` | @Redirect | 返回原 `level.getBiome(pos)` |
| PATHFINDING | `misc/PathFinderMixin` | @Redirect x4 + @ModifyVariable x2 | 各返回原值（重建 `set.stream()` / `Collectors.toMap` / `stream.collect` / `Sets.newHashSetWithExpectedSize` / 原变量） |

每个注入方法首行：`if (!ServerCoreConfig.isEnabled(ServerCoreConfig.Feature.XXX)) return;`（@Redirect 改为返回原值）。

## 六、配置示例（config/servercore.yml）

```yaml
enabled: true        # 总开关，false = 全部 ServerCore 优化关闭
sync-loads: true     # 蜜蜂巢/寻路/地图/结构 仅已加载区块才校验（蜜蜂 NPE 即此项，可临时关）
chunk-tickets: true  # 生成怪物/结构时不额外加区块票据
biome-lookups: true  # 生成怪物时快速生物群系查询
pathfinding: true    # 寻路 Map/Set 分配缩减
```

> 说明：arclight 仅移植了 ServerCore 的子集（上述 4 组），并非原版模组全部开关；本文件覆盖的是实际内置的优化项。

## 七、验收

1. 构建成功，jar 内含 `ServerCoreConfig` 类与 `sync-loads`/`chunk-tickets`/`biome-lookups`/`pathfinding` 字符串。
2. 默认 `servercore.yml` 不写时自动生成于 `config/`，全 true → 行为与 1.0.8 一致（蜜蜂仍被优化拦截但不崩）。
3. 把 `sync-loads: false` 后重启：蜜蜂 NPE 路径不再触发（BeeMixin 不注入）；其它组同理可独立关闭。
4. 测服启动 `Done` 无崩，蜜蜂正常 tick。

## 八、风险与回滚

- **风险**：@Redirect 关闭时重建原调用的返回值若写错，会使该优化失效乃至报错。对策：`enabled: true` 默认不触发关闭分支；关闭路径严格重建原始 INVOKE 目标。
- **回滚**：改回 `servercore.yml` 全 true 即恢复；或 `enabled: false` 一键回退全部；最坏情况删 `config/servercore.yml` 让其重建默认。源码改动可通过 git 还原（未提交前本地改）。
- 本次为**核心改动**：须重新构建 arclight-common（进 `.arclight/mod_file/` 的 common jar）并清空该缓存，否则旧 common 仍生效。
