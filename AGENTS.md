# AGENTS.md

## 项目介绍

PRTS 是 Arclight → Luminara 的 fork，本分支为**多线程并行引擎**开发分支：异步寻路、维度并行、区域并行、ThreadPolicy 自动路由、区块生成削峰、barrier 健壮性。

### 环境

- JDK: Java 21
- Minecraft: 1.21.1
- Modloader: NeoForge
- 构建: `./gradlew collect --rerun-tasks --no-daemon`（Windows 用 `gradlew.bat`）

## 约束（必须遵守）

你是一个精通 Minecraft 游戏机制、模组加载器机制的 Agent。**你必须将自己关进笼子**——开始任何工作前，完整阅读 `.cage/` 文件夹下的全部约束文档：

1. `.cage/README.md` — 红线清单：不允许做什么（先读这个）
2. `.cage/workflow.md` — 修 bug / 优化流程与验证口径
3. `.cage/architecture.md` — 线程模型、事件桥、锁纪律等架构不变量
4. `.cage/code.md` — 代码守则、目录放置、配置、提交规范
5. `.cage/capture.md` — 监测文件（抓包）处理流程
6. `.cage/scripts/` — 工具脚本（spark 解析 / RCON / 冒烟 / 字节码抓取）

### 任务速查

- **修 bug** → 按 `.cage/workflow.md` 状态机：登记 → 取证 → 定位 → 根因 → 修复 → 验证；复现非必经步骤，优先用用户日志，无日志按描述从代码推断，仍不定论才启动测试服自跑。
- **用户丢监测文件（文件夹 / zip）** → 按 `.cage/capture.md`：先读 `capture.json` 定触发原因，`sparkprofile` 用 `scripts/spark_quick.py` 解析，结论逐条带证据并按 P0 / P1 / P2 排序。
- **性能分析** → spark 归因先剔 idle 再算 self；同口径对比（交叉 A/B、预热、取中位）后才能下结论。
- **放代码** → 优化放 `optimization/<名>/`（平铺，模组直接用模组名），mixin 放 `mixin/optimization/<名>/` 成对；正确性兼容放 `compat/<mod>/`。详见 `.cage/code.md` §2。
- **提交** → 按仓库协作约定走功能分支 + PR / review；tag / release 由维护者决定；commit 英文小写 type、祈使句。

## 代码速览

- `arclight-common/.../optimization/` — 优化引擎实现（servercore、eventbridge、entityspatial 等）
- `arclight-common/.../mixin/optimization/` — 优化 mixin（与上成对）
- `arclight-common/.../compat/` — 跨平台模组兼容
- `arclight-neoforge/.../compat/` + `mixin/compat/` — 平台层模组兼容
- `arclight-common/.../compat/prts/` — PRTS 配置解析与默认模板

## 常用命令

```bash
./gradlew collect --rerun-tasks --no-daemon        # 构建（产物在 build/libs/）
python3 .cage/scripts/smoke_test.py                # 冒烟测试（需 SMOKE_DIR / RCON 环境变量）
python3 .cage/scripts/spark_quick.py p.sparkprofile out.json   # spark 采样解析
python3 .cage/scripts/rcon_cmd.py tps              # RCON 命令
```
