# v25 方案：核心 mixin 与 neobeefix 冲突崩溃 + create_hypertube 误隔离

> 适用分支：1.21.1（bootstrap + arclight-common，当前本地迭代版本 **1.0.6**，按铁律未升版号，未提交未发版）
> 作者：AI ｜ 日期：2026-08-01
> 状态：**方案待确认**（先文档后代码，用户确认后再改）

## 0. 背景

1.21.1 测服 `D:\mc\PRTS-1.21.1` 起服崩溃，同时 `create_hypertube` 被误隔离。两者是**互相独立**的问题：
- 崩溃是**核心 mixin**（`arclight-common`）与用户新加的 `neobeefix` 模组抢同一注入点导致，与 ClientModGuard 无关（自愈不捕获 mixin 注入错误）。
- create_hypertube 是 **ClientModGuard 预检误杀**（合法双端模组被"中毒 mixin"启发式整包隔离）。

## 1. 问题 A：核心 mixin 与 neobeefix 冲突崩溃

### 实证（来自 latest.log 21:55:52）

```
[WARN] @Redirect conflict. Skipping mixins.arclight.core.json:world.level.block.entity.BeehiveBlockEntityMixin
      ->@Redirect::arclight$bypassNightCheck(Lnet/minecraft/world/level/Level;)Z with priority 500,
      already redirected by neobeefix.mixins.json:BeehiveBlockEntityMixin from mod neobeefix
      ->@Redirect::noSkyLight_isNotNight(...) with priority 1000
...
Caused by: InjectionError: Critical injection failure: Redirector arclight$bypassNightCheck(...) failed injection check, (0/1) succeeded.
```

- 触发点：`arclight-common/.../mixin/core/world/level/block/entity/BeehiveBlockEntityMixin.java` 第 98-101 行的 `arclight$bypassNightCheck` `@Redirect`，重定向 `Level.isNight()`（让蜂巢在非夜晚也能释放蜜蜂，带 `arclight$force` 强制释放守卫）。
- 根因：`neobeefix` 的同目标 `@Redirect` 优先级 1000，高于我们的 500，Mixin 在冲突解析阶段**跳过我们**（WARN 已说明）。但 `InjectionInfo` 的注入后校验默认 `expect=1`（至少 1 个成功），我们的 redirector 0 成功 → 抛 `Critical injection failure` → `MixinTransformerError` → 启动崩溃。
- 关键：这与 ClientModGuard 完全无关（是核心 mixin 注入校验失败，非客户端类缺失），ClientModGuard 自愈三道门均不命中，不会自愈。

### 修复（改动 1 处，核心 mixin）

给 `arclight$bypassNightCheck` 的 `@Redirect` 加 `expect = 0`，含义"允许 0 个重定向成功（被其他 mod 抢走同目标时不报错）"：

```java
@Redirect(method = "releaseOccupant",
         at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isNight()Z"),
         expect = 0)   // v25：与 neobeefix 同目标抢占时允许 0 成功，优雅跳过不崩
private static boolean arclight$bypassNightCheck(Level world) {
    return !arclight$force && world.isNight();
}
```

- 代价：neobeefix 存在时，本 redirect 让位给 `noSkyLight_isNotNight`，我们的 `arclight$force` 强制释放守卫逻辑随之失效——但 neobeefix 已覆盖"蜂巢白天释放"同功能，属可接受权衡（不崩优于保留次要守卫）。
- 不改语义：若未来移除 neobeefix，本 redirect 仍正常生效（expect=0 仅放宽冲突时的失败判定）。

## 2. 问题 B：create_hypertube 误隔离

### 实证

- 现状：`_disabled_mods/create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 已被 ClientModGuard 隔离。
- 剖析 jar：它是 **Create 附属双端模组**（`mods.toml` 双端；含 `compat`/`conflict_fix`/`create_hypertube` 三个 mixins.json，其中 `core`/`compat` 段有服务端 mixin：`ServerGamePacketListenerImplMixin`、`EntityTravelingMixin`、`PlayerMixin` 等）。
- 隔离走哪条判据：`ClientModGuard.decide()` **L1051-1056 v15 L1 中毒 mixin**（硬证据路径）→ `detectPoisonMixin`（L1415/L1832：检测"注入服务端必加载的原版类 + 体内调用客户端类"）。具体是 `PlayerMixin`（双端 `mixins` 段）体内引用了客户端类（如 `Minecraft`/客户端 Screen 守卫），被整包隔离。
- 用户指正：它**不是客户端模组**，是合法双端模组。专用服上其双端 mixin 的客户端引用位于 `DistExecutor.runWhenOn(Dist.CLIENT, ...)` / `@OnlyIn(Dist.CLIENT)` 守卫之后，服务端不会真正执行那段 → **不崩**。属误杀。

### 修复（两档，本次默认只做 A 档）

**A 档（本次执行，零代码改动，立即可用）**
- 在 `guard.yml` 的 `allowlist` 加 `create_hypertube`（或文件名全小写 `create_hypertube-0.5.0-alpha-neoforge`）。
- 将 `_disabled_mods/create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 移回 `mods/`。
- 重新起服：预检读 allowlist 放行（`decide()` L1025 命中 `L0/user-restored` → KEEP），不再隔离。
- 回滚：从 allowlist 移除 + 移回 `_disabled_mods` 即可。

**B 档（后续可选，改代码，本次不做）**
- 改进 `detectPoisonMixin`：区分 mixin 来自 `client` 段（纯客户端模组）还是 `mixins`/`server` 段（双端模组）。仅"纯客户端模组的 `client` 段 mixin 注入服务端类"判中毒；**双端模组 `mixins` 段 mixin 引用客户端类属正常运行时守卫，不判中毒**。
- ⚠️ 精度优先铁律：此改动影响"中毒 mixin"这一硬证据路径，需充分验证防漏真中毒（漏检比误杀更危险），故**本次不碰**，列为后续增强。

## 3. 实施步骤（确认后执行）

1. 改 `BeehiveBlockEntityMixin.java` 加 `expect = 0`（问题 A）。
2. `guard.yml` 的 `allowlist` 加 `create_hypertube`；移回 `create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 到 `mods/`（问题 B·A 档）。
3. 重新构建 1.0.6（gradle-8.13 CLI jar + 临时 `skipSpigot` 补丁，**本地迭代不改版号**），部署 `D:\mc\PRTS-1.21.1` 覆盖旧 jar，清 `.arclight/mod_file/*`，还原补丁。
4. 起服验收：无 `failed injection check`、无 create_hypertube 误隔离、顺利 `Done`。

## 4. 风险与回滚

- A 档白名单是**配置改动**：回滚只需从 `allowlist` 移除 + 移回 `_disabled_mods`。
- 问题 A 的 `expect = 0`：回滚即移除该参数（仅在 `neobeefix` 同版本存在时才会回到崩溃态）。
- 全程本地迭代：版本号仍 **1.0.6**，未提交未发版（待用户授权再 commit/push/release）。

## 5. 待确认

- 问题 B 的 **B 档（改 `detectPoisonMixin` 区分 client/mixins 段）** 是否本次一起做？还是仅做 A 档（白名单豁免）？
- 确认后我再动代码 + 构建部署。
