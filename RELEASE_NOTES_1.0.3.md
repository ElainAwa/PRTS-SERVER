# PRTS 1.21.1 v1.0.3 (NeoForge)

基于 Luminara 1.21.1（Arclight Hybrid 的 NeoForge 下游 fork）的 PRTS 品牌重塑版本。

## 品牌重塑（PRTS rebrand）
- compat 包 `arclight-common/.../compat/luminara/*` 整包重命名为 `prts/*`（类名 `Luminara*` → `PRTS*`）
- 实体追踪 / 空间化附近玩家索引 / ticketpropagator 等优化 mixin 的品牌字符串同步为 PRTS
- i18n 横幅（`.conf`）品牌同步为 PRTS，与 1.20.1 一致
- README 项目血统修正为完整链路：**Arclight (IzzelAliz) → Luminara (QianMo0721) → PRTS（本仓库）**

## 版本
- `1.0.2-SNAPSHOT` → `1.0.3`（去除 SNAPSHOT，作为正式 release）

## 说明
- 本 release 为 NeoForge 服务端（`PRTS-neoforge-1.21.1-1.0.3.jar`）
- 框架包名保持 `io.izzel.arclight` 不变（Arclight 内部标识，不可改）
- 优化项（ServerCore 移植、NPI 空间化、动力铁轨优化、sync_loads 子集等）沿用 1.21.1 既有配置
