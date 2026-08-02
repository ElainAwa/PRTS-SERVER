# v30 — ServerCore Activation Range 全量移植（替换 Spigot 版）

适用树：`1.21.1/Luminara-FeudalKings`（NeoForge 1.21.1，源码 1.0.9 → **1.0.10**）
上游参照：`Wesley1808/ServerCore` 1.21.1（`D:\mc\617\1.6.5-server\1.21.1\ServerCore-ver-1.21.1`，包名 `me.wesley1808.servercore`）

---

## 1. 需求

用户原话：

> 「这是源码，功能能不能搬全」
> 「连同 activation_range 一起，而且要和原模组一样可配置，详见 servercore 的配置文件」
> 选项确认：**移植并替换 Spigot 版（推荐）**

拆解为三条硬性要求：

1. **搬全**：ServerCore 的 activation_range 特性（7 个核心类 + 18 个 mixin）逐项落地，不做功能裁剪。
2. **替换**：删除本树原有的 Spigot 版 activation range，避免两套"实体激活判据"叠加导致双重门控（实体被 Spigot 判为 inactive 后 ServerCore 再判一次，行为不可预测）。
3. **可配置**：配置项与原模组 `config.yml` 的 `activation-range:` 段**逐字一致**（键名、层级、默认值、注释），统一并入本树已有的 `config/servercore.yml`。

---

## 2. 实证

### 2.1 本树原有 Spigot 版清单（已删除，11 个文件）

```
bridge/optimization/EntityBridge_ActivationRange.java
mixin/optimization/general/activationrange/ActivationRangeMixin.java          # @Overwrite activateEntity
mixin/optimization/general/activationrange/EntityMixin_ActivationRange.java   # 继承链根
mixin/optimization/general/activationrange/ServerLevelMixin_ActivationRange.java
mixin/optimization/general/activationrange/entity/{AbstractArrow,AgeableMob,AreaEffectCloud,
        FireworkRocketEntity,ItemEntity,LivingEntity,Villager}Mixin_ActivationRange.java
```

删除后全树 `grep -rn "spigotmc.ActivationRange"` 无残留引用，`org.spigotmc.ActivationRange`
本体由 Spigot API 提供、不再有调用点，**无需再动**。

### 2.2 上游与本树的三处范式差异（决定移植写法）

| 项 | ServerCore 上游 | 本树 | 结论 |
|---|---|---|---|
| 接口暴露 | Loom **interface injection**，`Entity implements Inactive, ActivationEntity`，可直接 `entity.servercore$xxx()` | 无 interface injection，统一走 `bridge$` 接口 + 强转 | 新建 `EntityBridge_ActivationRange` / `GoalSelectorBridge_ActivationRange`，调用处强转 |
| mixin 继承 | `AgeableMobMixin extends PathfinderMob`（继承**原版类**） | `XxxMixin_ActivationRange extends EntityMixin_ActivationRange`（继承**父 mixin**）+ `@Shadow` 各自字段 | 沿用本树范式（无 interface injection 时唯一可编译写法） |
| MixinExtras | `@WrapWithCondition` / `@Local` | `grep mixinextras` **无任何依赖** | 改写为原生 `@Redirect` / `@Inject(HEAD, cancellable)` |

### 2.3 核心 `ServerLevelMixin` 的既有注入点（冲突排查）

`mixin/core/server/level/ServerLevelMixin.java`：

```
L299 @Inject(tickNonPassenger, INVOKE Entity.tick()V, shift=AFTER)      -> bridge$postTick()
L304 @Inject(tickPassenger,   INVOKE Entity.rideTick()V, shift=AFTER)   -> bridge$postTick()
L626 @ModifyVariable(tickNonPassenger, INVOKE Entity.tick()V)           -> captureTickingEntity
L632 @ModifyVariable(tickNonPassenger, INVOKE Entity.tick()V, AFTER)    -> resetTickingEntity
L638 @ModifyVariable(tickPassenger,   INVOKE Entity.rideTick()V)        -> captureTickingPassenger
L644 @ModifyVariable(tickPassenger,   INVOKE Entity.rideTick()V, AFTER) -> resetTickingPassenger
```

全树 `grep "tickCount:I"` 仅 `ServerPlayerMixin#doTick` 一处（`ServerPlayer.tickCount`，不同 owner），
**`Entity.tickCount` 的 PUTFIELD 无人占用**，ServerCore 的 tickCount 重定向可平移。

> `tickNonPassenger` 的 `INVOKE Entity.tick()` 已被 3 个核心注入器占用。若我方再上 `@Redirect`
> 替换该指令，虽然 Mixin 的 `InjectionNodes.replace()` 能让同批注入器跟随迁移，但**第三方优化模组**
> 若也重定向同一指令就会出现 0/1 critical failure（v25b 已踩过 `BeehiveBlockEntityMixin` 同款坑）。
> 故 `tickNonPassenger` 走 `@Inject(HEAD, cancellable)`（与 Spigot 原版补丁、与本树已删版本一致）。

### 2.4 ItemEntity 的 Forge 侧依赖必须保留

`arclight-neoforge/.../ItemEntityMixin_ActivationRange_NeoForge` 实现了
`ItemEntityBridge#bridge$forge$optimization$discardItemEntity()`（含 `lifespan`、
`spigotConfig.itemDespawnRate`、`EventHooks.onItemExpire`）。上游 ServerCore 的 `ItemEntityMixin`
是裸 `age >= LIFETIME -> discard()`，**会绕过 Forge 事件与 Spigot 掉落物寿命配置**。
移植时改调 `bridge$forge$optimization$discardItemEntity()`。

### 2.5 AccessWidener 缺口

`arclight.accesswidener`（v2 named，Loom 编译期应用）已含：`AbstractArrow inGround`、
`Mob goalSelector/targetSelector`、`Bee$BeePollinateGoal`、`SkeletonHorse trapTime`、`ItemEntity pickupDelay`。
**缺 3 条**（`checkEntityImmunities` 需要）：

```
accessible	field	net/minecraft/world/entity/LivingEntity	jumping	Z
accessible	field	net/minecraft/world/entity/animal/Bee	beePollinateGoal	Lnet/minecraft/world/entity/animal/Bee$BeePollinateGoal;
accessible	method	net/minecraft/world/entity/animal/Bee$BeePollinateGoal	isPollinating	()Z
```

---

## 3. 根因（为什么必须换掉 Spigot 版）

Spigot 版 activation range 的判据是**固定 4 组**（animal/monster/raider/misc）+ 单一
`activationRange` 矩形 AABB，无 tick-interval、无 wakeup-interval、无垂直范围、无自定义分组、
无村民恐慌/工作免疫，且 `activatedTick` 由 `@Overwrite activateEntity` 硬改。ServerCore 版在
Paper/Aikar 实现基础上补齐了这些维度，且**全部可配置**。两套同时存在时：

- `ServerLevelMixin_ActivationRange`（Spigot）在 `tickNonPassenger` HEAD 先判一次并 `cancel`；
- ServerCore 版再判一次 —— 后者永远拿不到执行权，配置形同虚设。

故必须二选一，按用户选择保留 ServerCore 版。

---

## 4. 备选方案对比

| 方案 | 说明 | 取舍 |
|---|---|---|
| A 保留 Spigot 版，仅加 ServerCore 配置壳 | 改动最小 | ❌ 无法提供 tick-interval / wakeup / 垂直范围 / 自定义分组，"搬全"不成立 |
| B 两套共存，用配置切换 | 灵活 | ❌ 双份实体字段 + 双份 mixin 注入点，维护与冲突成本翻倍；用户已明确选替换 |
| **C 删 Spigot 版，全量移植 ServerCore 版（选定）** | 单一实现，配置逐字对齐上游 | ✅ 满足三条硬性要求；代价是需重写 MixinExtras 注入器 |
| D 直接引入 MixinExtras 依赖后原样移植 | 代码最省 | ❌ 给 bootstrap/common 增加运行期依赖，且与 NeoForge 自带 MixinExtras 版本可能打架，风险高于收益 |

---

## 5. 选定方案与代码位置

### 5.1 新增核心类 — `arclight-common/src/main/java/io/izzel/arclight/common/optimization/general/servercore/activation_range/`

| 文件 | 内容 | 与上游差异 |
|---|---|---|
| `ActivationType.java` | POJO：`activationRange / tickInterval / wakeupInterval / extraHeightUp / extraHeightDown` | dazzleconf 接口 → 普通类 |
| `CustomActivationType.java` | `extends ActivationType` + `name` + `List<EntityTypeTest<? super Entity,?>> matchers` | 同上 |
| `EntityTypeTests.java` | 11 个 `typeof` 注册表（mob/monster/raider/ambient/animal/neutral/water_animal/flying_animal/flying_monster/villager/projectile） | 原样 |
| `ActivationRangeConfig.java` | POJO + `parse(Map)`，含 `EXCLUDE_TAG = "exclude_ear"` 常量 | 取代 dazzleconf + EntityTypeSerializer |
| `ActivationRange.java` | `initializeEntityActivationType / isExcluded / activateEntities / checkEntityImmunities / checkIfActive / shouldTick / checkInactiveWakeup / hasTasks` | `entity.servercore$xxx()` → `((EntityBridge_ActivationRange) entity).bridge$xxx()`；`Config.get().activationRange()` → `ServerCoreConfig.activationRange()`；`Util.hasTasks` 内联 |

### 5.2 新增 bridge — `arclight-common/.../bridge/optimization/`

- `EntityBridge_ActivationRange`：`bridge$getActivationType / isActivationExcluded / get|setActivatedTick /
  get|setActivatedImmunityTick / is|setInactive / getFullTickCount / incFullTickCount / inactiveTick`
- `GoalSelectorBridge_ActivationRange`：`bridge$inactiveTick`

### 5.3 新增 mixin — `arclight-common/.../mixin/optimization/general/servercore/activation_range/`

根与调度（2）：

| 文件 | 注入 | 上游写法 → 本树写法 |
|---|---|---|
| `EntityMixin_ActivationRange` | `<init> RETURN`（初始化 type/excluded）、`move` 活塞唤醒、`push(DDD)` inactive 时取消、`load RETURN` / `addTag HEAD` 读 `exclude_ear` 标签 | 接口注入 → `implements EntityBridge_ActivationRange` |
| `ServerLevelMixin_ActivationRange` | `tick`→每 20 tick `activateEntities`；`tickNonPassenger` **HEAD cancellable**；`tickPassenger` **@Redirect rideTick**；`tickPassenger` **@Redirect tickCount PUTFIELD** | `@WrapWithCondition` → `@Inject(HEAD)` / `@Redirect` |

inactive tick 继承链（11）：

```
EntityMixin_ActivationRange
├── LivingEntityMixin_ActivationRange            noActionTime++
│   └── MobMixin_ActivationRange                 goal/targetSelector 的 inactiveTick
│       ├── AgeableMobMixin_ActivationRange      age 向 0 收敛
│       │   ├── BeeMixin_ActivationRange         fullTickCount%20 且蜂巢失效 -> hivePos=null
│       │   ├── ChickenMixin_ActivationRange     eggTime-- 到点下蛋
│       │   ├── SkeletonHorseMixin_ActivationRange  陷阱马 trapTime 超时 discard
│       │   └── VillagerMixin_ActivationRange    unhappyCounter-- + maybeDecayGossip
│       └── TadpoleMixin_ActivationRange         setAge(age+1)
├── AbstractArrowMixin_ActivationRange           inGround -> tickDespawn()
├── AreaEffectCloudMixin_ActivationRange         tickCount++ 到期 discard
└── ItemEntityMixin_ActivationRange              pickupDelay--/age++ + Forge 侧 discard
```

> 与上游的两处收敛（已评估等价）：
> - 上游 `ArrowMixin` + `SpectralArrowMixin` 两份 → 合并为 `AbstractArrowMixin_ActivationRange`
>   （`ThrownTrident` 在 `isExcluded` 中已排除，不受影响）。
> - 上游 `ItemEntityMixin` 的 `age >= LIFETIME -> discard()` → 改调
>   `bridge$forge$optimization$discardItemEntity()`（见 2.4）。

GoalSelector 与 fixes（4）：

| 文件 | 作用 |
|---|---|
| `GoalSelectorMixin_ActivationRange` | 非激活时每 20 次调用真正 `tick()` 一次 |
| `CatMixin_ActivationRange` / `OcelotMixin_ActivationRange` | `@Redirect removeWhenFarAway` 的 `tickCount` GETFIELD → `fullTickCount`，修非激活猫/豹永不消失 |
| `PistonMovingBlockEntityMixin_ActivationRange` | `@Redirect Entity.setDeltaMovement(DDD)V` 唤醒被活塞推动的实体（上游用 `@Local`），`priority = 900` |

### 5.4 配置 — `ServerCoreConfig`

- 新增枚举值 `ACTIVATION_RANGE("activation-range")`？**不采用**：activation range 的开关就是
  `activation-range.enabled`，与上游一致，避免出现两个开关。
- 新增 `activationRange()` 返回解析后的 `ActivationRangeConfig`；
  `isActivationRangeEnabled()` = `master && cfg.enabled`（`master` 即已有的顶层 `enabled`）。
- `DEFAULT` 字符串追加 `activation-range:` 整段，键名/顺序/注释/默认值与上游 `docs/config/DEFAULT.md`
  L224-L360 **逐字一致**（`enabled: false` 默认关，与上游相同）。
- `entity-matcher` 解析：`typeof:<key>` 查 `EntityTypeTests`，否则按 `ResourceLocation` 查
  `BuiltInRegistries.ENTITY_TYPE`；无法解析的条目跳过并计数。

### 5.5 注册与版本

- `mixins.arclight.impl.optimization.json`：删 10 条 `activationrange.*`，加 17 条
  `servercore.activation_range.*`。
- `arclight.accesswidener`：补 2.5 节 3 条。
- `gradle.properties` / 版本号：`1.0.9 → 1.0.10`。

---

## 6. 验收

| # | 项 | 判据 |
|---|---|---|
| 1 | 编译 | `:bootstrap:neoforgeJar` 成功，无 `ClassNotFound` / `@Shadow` 解析失败 |
| 2 | 默认行为不变 | 全新 `servercore.yml` 中 `activation-range.enabled: false` → 服务器实体行为与 1.0.9 完全一致，无掉血/不动/不刷怪 |
| 3 | 开关生效 | 改 `enabled: true` 重启，远离玩家的动物/怪物停止 AI，靠近后 1 tick 内恢复 |
| 4 | 配置逐字对齐 | 生成的 `activation-range:` 段与上游 `docs/config/DEFAULT.md` diff 仅缩进/注释前缀差异 |
| 5 | 无 critical failure | 启动日志无 `Critical injection failure`，无 `mixin ... failed to inject` |
| 6 | 回归 | 掉落物按 `itemDespawnRate` 消失；猫/豹能正常消失；活塞推动的实体不卡住；村民工作/恐慌时被唤醒 |

---

## 6.1 实测暴露的两个坑（首轮启动失败 → 已修）

### 坑 1：`@Shadow` 不跨超类查找

首轮启动崩在 `Bootstrap`：

```
InvalidMixinException: @Shadow method playSound in ...ChickenMixin_ActivationRange
was not located in the target class net.minecraft.world.entity.animal.Chicken
```

Mixin 的 `@Shadow` 只在 **target class 自身声明的成员** 中解析，不会沿超类链向上找。上游 ServerCore 用接口注入 + `@Unique`，不受此限；本树用 mixin 继承链，子 mixin 若 `@Shadow` 了继承来的成员就会失败。

字节码核实结果（`javap -p`）：

| mixin | 成员 | 实际声明处 | 结论 |
|---|---|---|---|
| Chicken | `eggTime` / `isChickenJockey` | Chicken | ✅ 可 Shadow |
| Chicken | `playSound` / `spawnAtLocation` / `gameEvent` / `random` | **Entity** | ❌ 改强转 `(Chicken)(Object)this` 调 public 方法，`random` 改 `getRandom()` |
| Villager | `maybeDecayGossip` | Villager (private) | ✅ 可 Shadow（需带方法体） |
| Villager | `getUnhappyCounter` / `setUnhappyCounter` | **AbstractVillager** | ❌ 改强转 `(Villager)(Object)this` 调 public 方法 |

其余 15 个 mixin 的 shadow 成员经 `javap` 逐一核实，全部在各自 target class 自身声明，无需改动。

### 坑 2：Spigot `TrackingRange` 硬依赖被删的 `Entity.activationType`

第二轮启动到 `Done (1.631s)` 后首个 tick 崩溃：

```
NoSuchFieldError: Class net.minecraft.world.entity.Entity does not have member field
'org.spigotmc.ActivationRange$ActivationType activationType'
  at org.spigotmc.TrackingRange.getEntityTrackingRange(TrackingRange.java:33)
  at ChunkMap.localvar$...$trackingRange$updateRange → ChunkMap.addEntity
```

该字段原由已删除的 `activationrange.EntityMixin_ActivationRange` 注入。`org.spigotmc.TrackingRange` 是 Spigot 补丁类（不在本仓库源码，随 jar 分发），**Tracking Range 与 Activation Range 是两个独立特性**，前者仍由 `arclight.conf` 的 `use-activation-and-tracking-range=true` 启用。

字节码扫描 jar 全量 class，`org.spigotmc.ActivationRange` + `TrackingRange` 共访问三个自定义 Entity 字段：

```
Entity.activationType : Lorg/spigotmc/ActivationRange$ActivationType;
Entity.defaultActivationState : Z
Entity.activatedTick : J
```

（`SpigotTimings` 中的 `activatedTickEntity` 仅为计时器字符串常量，非字段访问。）

修法：新增 `mixin/optimization/general/trackingrange/EntityMixin_TrackingRange`，只补这三个字段并在 `<init>` RETURN 处调 `ActivationRange.initializeEntityActivationType`，**不参与任何 tick 门控**。已确认被删的 `ActivationRangeMixin` 仅 `@Overwrite` 私有 `activateEntity`，不影响 `initializeEntityActivationType` 的原生行为。

---

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `tickPassenger` 的 `@Redirect rideTick` 与第三方模组抢注入点 | 加 `require = 0, expect = 0`；失效时退化为"乘客始终 tick"，不崩服 |
| `checkEntityImmunities` 触及 `Bee#beePollinateGoal` 等私有成员 | 由 accesswidener 编译期打开；若 AW 未生效则编译期立刻报错，不会带病上线 |
| 新增实体字段增加内存 | 每实体 +4 int / 2 boolean / 1 引用，与上游一致 |
| 行为回退 | `activation-range.enabled: false`（默认值）即完全关闭，等价于 1.0.9 |
| 代码回滚 | 本次全部改动集中在 `servercore/activation_range` 目录 + 3 个接线文件（mixin json / accesswidener / ServerCoreConfig），`git checkout` 即可复原 |
