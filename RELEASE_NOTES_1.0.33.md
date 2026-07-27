# PRTS 1.20.1 v1.0.33

## 重大变更
- **品牌重塑 Luminara → PRTS**：服务端日志前缀、控制台横幅、mod ID（`@Mod("prts")`）、配置文件名（`prts.yml`）、命令（`/prts`）全部改为 PRTS。横幅采用 Slant 斜体字体，白色 PRTS 标识配原彩色副标题（Primitive Rhodesisland Terminal Service）。
- 框架内部标识保持不变以保证兼容性：Java 包名 `io.izzel.arclight`、`Automatic-Module-Name: arclight.boot`、`group`/`rootProject.name`、mixin 内部 `luminara$` 成员均不动。
- README（中/英）品牌同步为 PRTS，日志标签 `[Luminara-*]` 改为 `[PRTS-*]`。

## 修复
- **彻底修复 routeB 实体追踪下连锁挖矿「随机爆东西」**
  - 根因：原版 `ChunkMap.move()` 以欧氏距离维护实体的 `seenBy` 集合，而 routeB 以方格 / Chebyshev 距离判定可见性，两套几何同时维护同一个 `seenBy` 集合，在区块边界环带上产生振荡，导致边界实体漏发 / 重发，表现为「随机爆东西」。
  - 修复：接管 `ChunkMap.move()`，让 routeB 成为 `seenBy` 的唯一管理者，消除两套几何不一致；瞬移（teleport）场景仍放行原版逻辑，保证切换维度 / 重生不卡死。

## 变更明细
- `ChunkMap_TrackingMixin`：新增 `@Redirect` 接管 `move(ServerPlayer)`，非瞬移时跳过原版广播，交由 routeB 统一维护。
- `NearbyEntityTracking`：移除 `tick()` 内提前更新玩家 `prev` 坐标的调用，避免 move 与 routeB 读到不一致状态。
- `ServerPlayer_TrackingMixin`：将 `vmpTracking$updatePosition()` 调用移至 `ServerPlayer.tick()` 末尾，保证同 tick 内 move 与 routeB 状态一致。
- 版本号 1.0.32 → 1.0.33。

## 兼容性
- 既有核心优化 `ChunkMapMixin_Optimize` 的 `move()` 重定向通过 Noisium 互斥门控（`@LoadIfMod(NOISIUM, ABSENT)`），生产服未装 Noisium 时不加载，与本次修复无冲突。
