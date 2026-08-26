# 工具脚本

随约束文档分发的实用脚本，均为独立文件；spark 解析已自带 pb2 模块，无额外依赖。

## 监测文件分析

- `spark_quick.py` — 解析 spark 采样文件（`*.sparkprofile`）。用法：`python3 spark_quick.py profile.sparkprofile [out.json]`。已自带 `spark_sampler_pb2.py`，开箱即用；归因规则：先剔 idle（park / yield / wait）再归因，`self = inclusive − Σ(child)`。
- `spark_sampler.proto` — spark 采样 proto 定义（需要重新生成 pb2 时使用）。

## 测试服调试

- `rcon_cmd.py` — 极简 RCON 客户端：`python3 rcon_cmd.py <命令>`；环境变量 `RCON_HOST` / `RCON_PORT` / `RCON_PASSWORD`。
- `smoke_test.py` — 冒烟测试：`SMOKE_DIR=<服务器目录> python3 smoke_test.py`，输出 `smoke-report.log` 并返回 0 / 1；检查启动 Done、无 FATAL、RCON tps、`BEPolicy unsafe=0`、`Journal` 无丢弃等。

## 字节码取证

- `ClassDumpAgent.java` — 自写 Java agent，抓任意类的字节码（观察加载 / retransform）：`-javaagent:ClassDumpAgent.jar=match=<类名子串>;out=<输出目录>`；`list=1` 只列已加载类。
- `MANIFEST.MF`、`build_agent.bat` / `build_agent.sh` — agent 打包清单与打包命令。
