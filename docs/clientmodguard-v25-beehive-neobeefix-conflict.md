# ClientModGuard v25 — 启动崩溃（核心 mixin 与 neobeefix 冲突）+ create_hypertube 误杀

> 适用分支：1.21.1（bootstrap + arclight-common，本地迭代，版本号维持 1.0.6，未升版、未发版）
> 关联：v23 迁移 / v24 自愈第三门 / v24b 启动期接线

## 1. 现象（2026-08-01 21:55 启动崩溃）

```
[main/ERROR] ... @Redirect conflict. Skipping mixins.arclight.core.json:world.level.block.entity.BeehiveBlockEntityMixin
   from mod (unknown)->@Redirect::arclight$bypassNightCheck(Lnet/minecraft/world/level/Level;)Z with priority 500,
   already redirected by neobeefix.mixins.json:BeehiveBlockEntityMixin from mod neobeefix
   ->@Redirect::noSkyLight_isNotNight(Lnet/minecraft/world/level/Level;)Z with priority 1000

Caused by: org.spongepowered.asm.mixin.injection.throwables.InjectionError:
   Critical injection failure: Redirector arclight$bypassNightCheck(...) failed injection check, (0/1) succeeded.
   Scanned 0 target(s). No refMap loaded.
...
[io.izzel.arclight.boot.neoforge.application.ApplicationBootstrap:accept:62]: Fail to launch Arclight.
```

- 崩溃发生在 **bootstrap mixin 注入阶段**（`net.minecraft.world.level.block.Blocks.<clinit>` → `FireBlock.bootStrap` → `Bootstrap.bootStrap`），早于模组加载与主逻辑。
- 这是 **Arclight 核心 mixin**（`mixins.arclight.core.json`）与第三方修复模组 **neobeefix**（用户 21:49 安装 `neobeefix-1.21.1-2.0.1.jar`）抢同一处重定向 `Level.isNight()` 所致。
- **不是客户端模组问题**，ClientModGuard 自愈（handleCrash 三道门：客户端类缺失 / ModLoading 失败 / clientOrigin 类加载错误）均不命中 —— 这是 `MixinTransformerError`（RuntimeException 子类，非 LinkageError/ClassNotFoundException），自愈不会触发，属正确行为。

## 2. create_hypertube 误杀（同一轮 21:50 预检）

- `_disabled_mods/create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 被隔离，但它是 **Create 附属双端模组**（依赖 create 6.0.x，另有 betterthirdperson/sable 可选依赖）。
- 其 `create_hypertube.mixins.json`：
  - `mixins`（服务端生效）：EntityTravelingMixin / IPlayerExtensionMixin / **PlayerMixin** / PlayerMovementMixin / ServerGamePacketListenerImplMixin
  - `client`（客户端生效）：Camera* / LocalPlayerMixin / PlayerModel*（专用服不加载）
- `PlayerMixin` 在**服务端 mixin 段**，但其字节码引用了客户端类（`net/minecraft/client/**`、`com/mojang/blaze3d/**`），被 ClientModGuard v15 L1「中毒 mixin」启发式判为硬证据 → 整包隔离。
- 实为**误判**：`PlayerMixin` 的 `@Mixin` 目标是服务端类（`Player`/`ServerPlayer`），客户端引用只是运行时守卫调用（专用服不会崩）。这与 sodium 那类「mixin 注入原版类却调客户端类导致 MixinPreProcessor 解析失败」的真·中毒不同。
- 正确处置：加入白名单（`guard.yml` `allowlist`），走 `!inWhite` 豁免逻辑（`decide()` L1051 的 `&& !inWhite` 守卫），不再隔离。

## 3. 修复

### 3.1 崩溃：BeehiveBlockEntityMixin 冲突（代码）
`arclight-common/.../mixin/core/world/level/block/entity/BeehiveBlockEntityMixin.java`
`arclight$bypassNightCheck` 的 `@Redirect` 加 `require = 0`：

- 语义：该重定向为「可选注入」。当 neobeefix（或任何更高优先级模组）已占用 `Level.isNight()` 同注入点时，本重定向被跳过（`0/1` 不再视为失败），不抛 `InjectionError`，服务正常启动；neobeefix 的 `noSkyLight_isNotNight` 生效。
- 当 neobeefix 不存在时，本重定向正常 `1/1` 应用，保留 Arclight 原有 `arclight$force` 强制释放语义。
- 比「抬优先级压过 neobeefix（priority=2000）」更友好：尊重用户主动安装的 neobeefix 行为，且对任意未来冲突模组都「优雅跳过不崩服」。

```java
@Redirect(method = "releaseOccupant",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isNight()Z"),
    require = 0)   // 与 neobeefix 同目标重定向冲突时优雅跳过，不崩服
private static boolean arclight$bypassNightCheck(Level world) {
    return !arclight$force && world.isNight();
}
```

### 3.2 误杀：create_hypertube 白名单豁免（配置）
`D:\mc\PRTS-1.21.1\_clientcheck\guard.yml`：
```yaml
allowlist:
  - create_hypertube
```
并将 `_disabled_mods/create_hypertube-0.5.0-ALPHA-NEOFORGE.jar` 移回 `mods/`。
- `decide()` 走 `inWhite || BUILTIN_SAFE.contains(id) || envServer` → `v = KEEP`（src=L0/allowlist），且 L1051 中毒检查被 `!inWhite` 跳过，双重保障不再隔离。

## 4. 验收
1. 构建 `:bootstrap:neoforgeJar`（skipSpigot 临时补丁），`BUILD SUCCESSFUL`，jar 内 `BeehiveBlockEntityMixin.class` 含 `require`/`arclight$bypassNightCheck`。
2. 部署测服 `D:\mc\PRTS-1.21.1`，清 `mods/*.jar` 提取缓存（`mod_file`），启动：
   - 不再出现 `Critical injection failure ... BeehiveBlockEntityMixin` / `Fail to launch Arclight`。
   - 预检日志：`create_hypertube` → `KEEP [L0/allowlist]`，不再进 `_disabled_mods`。
   - 其它原隔离客户端模组（sodium/iris/ambientsounds/lambdynamiclights 等）仍按预期隔离。
   - 最终 `Done (...)` 正常进入。
3. 版本号维持 `1.0.6`（本地迭代，未升版、未提交、未发版）；验收通过后若发版再升 `1.0.7` 走 commit+push+release。

## 5. 风险 / 回滚
- `require = 0` 仅影响 beehive 夜间检查重定向的「共存」行为，不改变 Arclight 既有逻辑正确性；若担心 neobeefix 行为覆盖，可改 `priority = 2000` 反向压过（二选一，不共存）。
- 白名单误加可用 `guard.yml` 删除 `create_hypertube` 一项回滚；模组移回 `mods/` 为常规文件操作。
- 回归：如其它模组也因「服务端 mixin 段引用客户端类」被误杀，统一用 `allowlist` 处理，不改动启发式（保持中毒 mixin 硬证据对真·中毒的拦截力）。
