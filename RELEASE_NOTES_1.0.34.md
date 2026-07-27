# PRTS 1.20.1 v1.0.34

## 修复
- **`/prts` 命令"未知命令"bug**：事件注册器补上 `PRTSCommand` 的注册（此前命令从未挂进 dispatcher，输入 `/prts` 报未知命令）
- **Krypton 模组兼容**：Krypton `@Overwrite` 了 `ChunkMap.move`，entitytracking 优化 mixin 不再注入 `move`，仅保留 `tick()` 的 AreaMap 空间化优化；`move` 实体遍历交由 Krypton/原版处理，几何一致不振荡

## 说明
- 优化行为不变：`optimization.experimental-optimizations-enabled` 门控依旧，关闭时 100% 原版行为

## 资产
- `PRTS-1.20.1-1.0.34.jar`（Forge 47.4.16 / MC 1.20.1）
