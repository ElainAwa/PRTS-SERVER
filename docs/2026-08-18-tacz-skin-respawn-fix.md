# TACZ / SkinsRestorer 皮肤刷新导致枪械无法开火（仅改 arclight，不动 TACZ/SR）

**日期**：2026-08-18
**分支**：1.21.1-Multithreading
**改动**：仅 `arclight-neoforge`（服务器核心），一处 mixin。TACZ、SkinsRestorer 源码未动。

## 现象
`/skin set`（或离线模式进服自动设置皮肤）后，TACZ 1.21.1 neoforge 枪械无法开火，需重新切枪或重进才恢复。

## 根因
1. 在 Arclight（非 Paper 的混合服务器）上，SkinsRestorer 1.21.x 的刷新走 `Mapping1_21`：向客户端推一个 `ClientboundRespawnPacket`（`KEEP_ALL_DATA`）+ 删除/重加自身 tab 条目——即「假重生」。
2. 客户端同维度、keep-data 重生路径**不再调用 `LocalPlayer.respawn()`**（1.21.1 中该方法已删除，MCP 复核无此方法）。因此 TACZ 客户端自带的恢复钩子 `LocalPlayerMixin.onRespawn`（注入 `LocalPlayer.respawn`）**静默不生效**。
3. 若皮肤刷新与开枪的 `scheduleAtFixedRate` 连发任务发生竞争：`shoot()` 已把 `isShootRecorded` 置 false、状态锁被非开枪锁占用；`doShoot` 第 1 轮因锁被占而不恢复 `isShootRecorded`、第 2 轮达 `maxCount` 直接取消。此后客户端读到 synced 冷却仍>0，指针锁一直不释放 → 枪永久无法开火。

服务端可见的症状位：TACZ 通过附在玩家上的 `DataHolder` 把**冷却/开火态同步给客户端**（`ModSyncedEntityData` 各 Key）。假重生后这些冷却没被重置重新同步，客户端据此判定状态锁不释放。

## 修复（仅服务器侧）
在 `ServerCommonPacketListenerImplMixin_NeoForge` 注入 `send(Packet)`：当推送给玩家的包是 `ClientboundRespawnPacket` 时，通过**反射**调用 TACZ 公共接口 `com.tacz.guns.api.entity.IGunOperator#initialData()`，把该玩家的枪械操作者状态整体复位：
- synced 开火/切枪/冲刺/近战/拉栓冷却归零，`currentGunItem` 重设为当前主手
- 下次 `onTickServerSide` 把这些 0 值同步给客户端 → 客户端状态锁 / `isShootRecorded` 恢复 → 可开火

设计要点：
- **反射，不对 TACZ 建编译依赖**；TACZ 未装或方法名变化时 try/catch 空操作，不影响其它服务器。
- **不区分真假重生**：真重生本就走 `ServerPlayerMixin.restoreFrom → initialData()`，这里再补一次是幂等无害；假重生（皮肤）则正好补上缺失的恢复。
- `this` 强转 `ServerGamePacketListenerImpl` 取 `player`；非游戏连接的 `ServerCommon` 子类（config/status/handshake）不推 `ClientboundRespawnPacket`，instanceof 兜底。
- 非主线程发送时 `server.execute(...)` 归队到主线程调度，避免与实体 tick 竞争（TACZ 枪械态是主线程所有）。

## 产物
```
bootstrap/build/libs/PRTS-neoforge-1.21.1-1.0.36-Multithreading.jar   ← :bootstrap:neoforgeJar
build/libs/PRTS-neoforge-1.21.1-1.0.36-Multithreading.jar            ← :collect（部署用，时间戳 08-18 12:10）
```
内嵌 `common.jar` 已核对含 `...ServerCommonPacketListenerImplMixin_NeoForge.class` 且 `mixins.arclight.neoforge.json` 引用在位。

## 验证方式（测试服 prts-test）
- 把新 `build/libs/PRTS-neoforge-*.jar` 放到 `prts-test/` 根目录替换旧 jar，启动后 `/skin set`（或离线进服自动设肤），持枪开火应正常。
- 边界自检：死亡重生、末地维度切换后仍能开火（真重生路径回归）；`currentGunItem` 在重生后被重设为主手，不会丢枪。

## 已知极限
纯服务器侧无法直接翻转客户端本地布尔 `isShootRecorded`；本修复通过把 synced 冷却归零释放客户端状态锁，令其连发/指针锁恢复路径自然复位。若将来出现极端竞态（连发任务被 overheat/死亡取消后再未切枪），客户端仍需一次切枪/重进——这类纯客户端残留不做服务器侧 hack。根因若要彻底消除，需 TACZ 客户端恢复钩子适配 1.21.1（本次约束禁止改 TACZ，故采用服务器侧等效手段）。