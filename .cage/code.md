# 代码 / 配置 / 提交约束

## 1. 写代码守则

1. 新能力默认值由评估（头脑风暴）决定并记录理由；不改变现有默认行为，除非评估结论明确要求。
2. 配置三件套同步：解析代码 + 默认值 + 生成模板；配置新增带模板注释，开关注释一行内说清。
3. 修 bug 注释写：根因 + 触发条件 + 证据（逐条列日志行 / JFR 计数 / 字节码条件）。
4. 关键状态可观测：`/servercore status`、日志、计数器；`cross.read` 是首要观测指标。
5. BE tick 抛 Throwable → 降级安全阀（回主线程），不允许 worker 崩溃蔓延。
6. 批改写用 Python：先备份，每个替换前 `assert old in s`，替换后核对。
7. 构建后核对 MANIFEST 的 Implementation-Version（dirty 指纹 = 有未提交改动，不允许带着 dirty 交付）。
8. 改 `arclight-common` 后必须清缓存（`mod_file/*`、`gson.jar`、`class_cache`），否则 mixin 会静默失效；验收前用 `-Dmixin.debug.export=true` 确认注入生效。

## 2. 目录与放置规范

代码按「优化」与「兼容」两类分放，判断标准：目标是提升性能 → 优化；目标是修复与并行引擎 / 平台不兼容的正确性问题 → 兼容。

**优化**（含针对模组的优化）一律放 `arclight-common` 的 `optimization/` 下，引擎实现与 mixin 同目录名成对出现：

- 引擎实现：`optimization/<名>/`，`<名>` 按功能名或模组名**平铺**，不嵌套——PRTS 自有功能用功能名（如 `eventbridge`、`entityspatial`、`menubroadcast`）；模组相关的直接用模组名（`servercore`、`create` 本身就是模组，对应实现/优化就放 `optimization/servercore/`、`optimization/create/`，不挂在其他目录下）。
- 对应 mixin：`mixin/optimization/<名>/`。
- 目录名小写、见名知意；一个目录一个配置段。

**模组兼容**（正确性修复）放 `compat/<mod>/`：

- 跨平台逻辑收敛到 `arclight-common` 的 `compat/<mod>/`（如 `compat/prts`、`compat/pluginfix`），子包直接按模组名命名。
- 平台特定逻辑放平台层 `arclight-neoforge/.../compat/<mod>/`，mixin 放 `arclight-neoforge/.../mixin/compat/<mod>/`（如 `compat/tacz`）。
- 类名按功能命名、不带模组名后缀（如 `GunOperatorCompat`、`RespawnPacketHandlerMixin`），模组归属由目录表达。

判断模糊时：问「去掉它性能是否回退」——回退 = 优化；兼容性修复即使顺带带来性能提升仍算兼容，放 `compat/`。

## 3. 语义一致性红线

- 优化后的行为与原版逐位一致：顺序、结果集、异常、时机零变化；保序、零漏检、谓词语义零变化。
- 事件计数、返回顺序、异常、线程违规数为回归断言项（见 `workflow.md` §10）。
- 不做「收益存疑但先上」的改动；无收益的立项必须记录取消理由。

## 4. 构建与测试环境

- JDK 21；构建命令 `./gradlew collect --rerun-tasks --no-daemon`（Windows 用 `gradlew.bat`）；构建目标为当前开发主线。
- 本地测试服：`prts-test/`，固定启动参数（Aikar 系 flags：`-Xms1G -Xmx5G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200`），开 RCON。
- 冒烟检查单每项必须过（启动 Done 无 FATAL、RCON tps/list、配置行与预期一致、`BEPolicy unsafe=0`、`Journal dropped*=0`、功能矩阵：传送 / 放拆方块 / 漏斗传输 / 村民认领 / 跨维度 / 重启）。

## 5. 提交规范

- 维护者说「发布」才 tag / release；未经授权不发布。
- commit 消息：英文、小写 type（`feat` / `fix` / `perf` / `docs` / `chore` / `telemetry` / `refactor` / `build`）、祈使句；可带范围如 `feat(S3.2): ...`。
- PR：**标题用英文**（直接用对应 commit 消息），**PR 正文必须用中文**。
- 提交作者：协作者使用自己的 git 身份，除非维护者另行指定。
- 协作方式：按仓库当前约定走功能分支 + PR / review，不强制单一分支；不向受保护分支直接强推。
- 提交前自查：不改进证据文档；不带未清理的门控探针；不带 dirty 构建产物。
