# PRTS 1.21.1 (NeoForge) v1.0.4

## 新特性
- **`/prts` 命令移植到 1.21.1**：命令逻辑位于 arclight-common，三个 loader（NeoForge / Forge / Fabric）分别接注册钩子
  - `prts info`：服务端版本 / Bukkit 版本 / 在线人数 / 内存占用 / 可用处理器
  - `prts help`：子命令帮助
- **Slant ASCII 横幅终版**：figlet Slant 字体加宽版 `P R T S` + 全称行 `Primitive Rhodesisland Terminal Service`（首字母高亮）+ 彩色副标题，全称与 logo 左对齐

## 验证
- NeoForge 冒烟：`Done (7.533s)!`，无错误
- RCON 实测 `prts` / `prts info` / `prts help` 全部正常
- `[PRTS-*]` 优化日志前缀正常（AsyncLog / EntityTrack / Features / NPI / TP）

## 资产
- `PRTS-neoforge-1.21.1-1.0.4.jar`（生产使用）
- `PRTS-forge-1.21.1-1.0.4.jar`
- `PRTS-fabric-1.21.1-1.0.4.jar`
