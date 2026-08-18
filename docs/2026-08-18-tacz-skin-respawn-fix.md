# TACZ / SkinsRestorer 皮肤刷新导致枪械无法开火（仅改 arclight，不动 TACZ/SR）

**日期**：2026-08-18
**分支**：1.21.1-Multithreading
**改动**：仅 `arclight-neoforge`（服务器核心），一处 mixin + 一个反射桥接类，收敛在专门命名空间 `compat.tacz`。TACZ、SkinsRestorer 源码**未动**。
**状态**：已实机验证可开枪（prts-test）。

## 现象
`/skin set`（或离线模式进服自动设置皮肤）后，TACZ 1.21.1 neoforge 枪械无法开火。**换弹正常**，重拉枪（切槽位）也无效，需重进才恢复。

## 根因（经三层诊断确认）
1. Arclight（非 Paper 混合服）上，SkinsRestorer 1.21.x 刷新走 `Mapping1_21`：向客户端推假 `ClientboundRespawnPacket`（`KEEP_ALL_DATA`）+ 删/重加自身 tab。
2. 1.21.1 客户端同维度 keep-data 重生**不再调用 `LocalPlayer.respawn()`**（该客户端复原方法已删，MCP 复核无）。TACZ 客户端自带的恢复钩子 `LocalPlayerMixin.onRespawn` 因此**静默失效**。
3. **关键**：TACZ 只在该玩家挨个同步值**发生变化(dirty)**时才把枪械状态（换弹/切枪/拉栓/冷却等 synced 标志）推给客户端。假重生的处理时序可能把"归零后第一次 dirty 推送"吞掉；之后这些值不再变化、永远不再推送 → 客户端**永久读到卡住的占用标志**（例如一直表现为"换弹/切枪中"），`shoot()` 在 `preCheck` 直接返回，**一发 `ClientMessagePlayerShoot` 都不发**。

**三层诊断结论**（服务端观测）：
- `initialData()` 归零后服务端冷却=0，但照旧打不出 → 不是服务端冷却。
- 客户端从未收到过开火包（`shoot-packet arrived` 一行都没有），且全自动也不打 → 卡点在客户端发枪前的 `preCheck` 同步态。
- 换弹正常（换弹不检查该同步标志）、重拉枪无效（重拉不清该标志）→ 唯一自洽解释即上述"客户端同步态卡死"。

## 修复（仅服务器侧，`compat/tacz`）
`send(Packet)` 注入：当推给玩家的包是 `ClientboundRespawnPacket` 时：
1. 反射调用 TACZ `IGunOperator#initialData()` 归零服务端冷却。
2. 再**延迟约 200ms**（等客户端处理完重生）**直接重推一份完整同步状态**给该玩家——复刻 TACZ `SyncedEntityDataEvent.onPlayerJoinWorld` 的直推，`gatherAll()` 玩家 `DataHolder` 后构 `ServerMessageUpdateEntityData` 包发出,**绕开 dirty 门**——即便没有值变化也强制刷新，从而把客户端卡住的占用标志清成空闲 → 恢复开火。

设计要点：
- **反射**，无编译依赖 TACZ；TACZ 未装/签名变化时 try/catch 空转。
- 服务器侧调度（`ScheduledExecutorService`）→ `server.execute()` 归队主线程再发包，避免与实体 tick 竞争。
- `this instanceof ServerGamePacketListenerImpl` 取 `player`；非游戏连接不推重生包，instanceof 兜底。
- 不区分真假重生；真重生额外补发一次同样安全（幂等）。

## 代码位置（专门命名空间）
```
arclight-neoforge/src/main/java/io/izzel/arclight/neoforge/
├─ compat/tacz/TaczGunOperatorCompat.java                        # 反射桥 + initialData + 延迟重推 + 诊断快照
└─ mixin/compat/tacz/TaczRespawnPacketHandlerMixin.java          # @Inject send(Packet)/handleCustomPayload
```
`mixins.arclight.neoforge.json` 增加 `"compat.tacz.TaczRespawnPacketHandlerMixin"`。核心 `ServerCommonPacketListenerImplMixin_NeoForge` 已还原为空桥接。后续 TACZ 兼容逻辑统一放 `compat/tacz`。

## 产物
```
bootstrap/build/libs/PRTS-neoforge-1.21.1-1.0.36-Multithreading.jar
build/libs/PRTS-neoforge-1.21.1-1.0.36-Multithreading.jar       ← :collect 部署用
```
内嵌 `common.jar` 已核对含 `compat/tacz/TaczGunOperatorCompat.class`、`mixin/compat/tacz/TaczRespawnPacketHandlerMixin.class`，JSON 引用在位。

## 验证（prts-test）
- 新 `build/libs/PRTS-neoforge-*.jar` 放 `prts-test/` 根目录启动 → `/skin set`/离线自动设肤后持枪可开火。日志可见：
  - `[Arclight-TACZ] reset-operator ok ...`
  - 约 200ms 后 `[Arclight-TACZ] re-push synced gun state ok ...`
- 边界回归：死亡/维度重生后开火正常；`currentGunItem` 重生后被重设为主手；`re-push FAILED` 时查反射签名。

## 已知局限
该修复靠"强制重推同步态"让客户端自愈，是针对当前机制的服务端等效手段。若未来 TACZ 同步机制变化或出现连发任务被取消的极端残留，可能仍需客户端侧钩子（本次约束禁止改 TACZ，故未采用）。诊断日志（`shoot-packet arrived`）保留作排障，实机已无性能影响（仅打枪时 info 一行）。