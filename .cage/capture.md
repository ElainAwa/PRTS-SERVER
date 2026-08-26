# 监测文件（抓包）处理流程

> 用户丢出一个文件夹或 zip 并指明是「监测文件」时，按本流程处理。目的是把抓包变成可归因的结论，而不是猜测。

## 1. 识别

文件夹 / zip 内含以下特征文件（不必全有）即判定为监测文件：

- `capture.json` — 抓包元数据（触发原因、时间窗、spark 代码）
- `*.sparkprofile` + 同名 `.json` — spark 采样数据与元数据
- `latest__tail.log` / `debug__tail.log` — 日志尾部
- `tps.txt`、`pre_history.jsonl`、`series.jsonl` — TPS 快照与时间序列
- `servercore_status.txt`、`spark_healthreport.txt`、`list.txt`
- 可选 `crash__*.txt` — 崩溃报告

zip 先解压到独立临时目录再分析，不要就地读压缩包。

## 2. 读取顺序与信息含义

1. `capture.json` — 先读。`reason` 是触发原因（如 `tps_below_threshold` = TPS 低于阈值自动抓包）；`started` / `ended` 是时间窗；`min_tps_1m` 是过程最低值；`spark_code` 对应 spark 文件名。
2. `tps.txt` — 抓包时刻的 TPS 三档 + 内存。
3. `series.jsonl` / `pre_history.jsonl` — 抓包期间 / 抓包前的 TPS 时序（约 5 秒一条，含 1m / 5m / 15m）。回答：在恶化还是恢复？低点在哪？持续多久？
4. `servercore_status.txt` — 并行引擎状态：BEPolicy（allow / force / unsafe）、Journal 丢弃计数、drain 统计、路由学习状态。
5. 日志 tail（`latest__tail.log` / `debug__tail.log`）— 按优先级找：
   - `Can't keep up` 落后量与频次；
   - `waiting in parallel barrier for Xms`——注意 X 是服务器当前落后量，不是主线程等待时长；
   - ThreadPolicy 违规（R / W 计数、违规位置坐标）；
   - `UUID of added entity already exists`——实体重复添加，潜在物品复制；
   - FATAL / 异常堆栈；
   - 备份 / 自动保存事件（与停顿时间点对照）。
6. `*.sparkprofile` — spark 采样（二进制）。用 `scripts/spark_quick.py` 解析：先剔 idle（park / yield / wait）再归因，`self = inclusive − Σ(child)`。重点看：主线程 vs 并行线程（RegionTick / DimensionTick / AsyncPathfinding）实际工作量；方块实体 / 实体 / 区块 tick 的 drain 账本；事件派发占比。
7. 同名 `.json` — spark 元数据：平台与版本、采样间隔、内存 / GC 数据。
8. `crash__*.txt`（若有）— 崩溃报告，先读堆栈头部。
9. `list.txt` — 在线玩家数（负载背景）。

## 3. 分析套路

1. 先定性：TPS 低是主线程过载、停顿（barrier / 锁 / 备份）、正确性违规、资源（内存 GC）中的哪一类，还是组合。
2. 主线程时间账：把采样周期内主线程时间拆到方块实体 drain / 实体 tick / 区块 tick / 事件派发，找最大项。
3. 并行系统检查：worker 是空转还是真忙？BEPolicy 是否全部拦截（allow=0，所有 BE 挤主线程）？违规计数在涨还是在收敛？
4. 停顿事件：`Can't keep up` 峰值、barrier 等待、与备份 / 自动保存时间点交叉对照。
5. 正确性：UUID 重复、`main_only_write` 的来源与频率。
6. 交叉验证：每条「原因」必须有至少一条直接证据（日志行 / 计数 / 采样归因），且与时间线吻合。

## 4. 结论输出规范

- 每条结论必须带证据（文件名 + 具体内容 / 计数）；无证据不下结论。
- 对引擎 / 模组行为的断言先对照源码或字节码核实（见 `workflow.md` §5 定位）。
- 建议按性价比排序：零风险杠杆（配置、预算、清怪）→ 小步并行，标注优先级（P0 / P1 / P2）。
- 输出为对话总结或新文档；涉及修复的，按 `workflow.md` 修 bug 流程走。

## 5. 坑

- `spark_healthreport.txt` 可能是空文件，正常，不代表异常。
- `sparkprofile` 是 protobuf 二进制，不要当文本读；解析依赖 spark 的 pb2 模块（按 `spark_quick.py` 用法提供）。
- 日志只有尾部（约 512KB），更早的时间线用 `pre_history.jsonl` / `series.jsonl` 的 TPS 走势补齐。
- 时间戳格式：`series.jsonl` 带时区、`pre_history.jsonl` 不带，属同一本地时间，对照时注意格式差异。
- 「理论热点」不算证据：采样归因与源码核实后才能立项（见 `README.md` 红线 8）。
