"""P3 v05 实体管理锁压测：双区域各 150 实体，5 分钟，检查 Index -1 类崩溃。"""
import subprocess
import sys
import time

HOST, PORT, PWD = "127.0.0.1", 25575, "prts123"
PY = r"C:/Users/Soft_/AppData/Local/Programs/Python/Python314/python.exe"
LOG = r"D:\mc\PRTS-1.21.1\server-out-v05b.log"


def rcon(cmd):
    try:
        out = subprocess.run(
            [PY, r"D:\mc\PRTS-1.21.1\_chk\rcon.py", HOST, PORT, PWD, cmd],
            capture_output=True, text=True, timeout=15, cwd=r"D:\mc\PRTS-1.21.1",
        ).stdout.strip()
        return out
    except Exception as e:
        return f"ERR:{e}"


def check_log(prefix):
    with open(LOG, encoding="utf-8", errors="replace") as f:
        content = f.read()
    fatal = content.count("FATAL")
    idx = content.count("Index -1")
    aob = content.count("ArrayIndexOutOfBoundsException")
    exc = content.count("Exception in server tick")
    print(f"[{prefix}] FATAL={fatal} Index-1={idx} AIOOBE={aob} serverExc={exc}")
    return fatal + idx + aob + exc


def main():
    print("=== 服务器活性 ===")
    print(rcon("time query daytime")[:40])

    print("=== 清理旧实体 ===")
    for x in (-16, 32):
        print(rcon(f"execute in minecraft:overworld run kill @e[type=minecraft:sheep,x={x},dx=40,dy=300,dz=40]")[:50])

    print("=== 批量 summon（region1 chunk-1 x=-16..-9 + region0 chunk0 x=0..7，Invulnerable 防摔落，AI 保留触发移动）===")
    for i in range(150):
        rcon(f"summon minecraft:sheep {-16 + i % 8} 72 {8 + (i // 8) * 3} {{Invulnerable:1b}}")
        rcon(f"summon minecraft:sheep {0 + i % 8} 72 {8 + (i // 8) * 3} {{Invulnerable:1b}}")
        if i % 25 == 0:
            print(f"  spawned {i * 2}/300")
            check_log(f"spawn@{i * 2}")
    print("=== 300 实体就位 ===")
    print(rcon("list")[:50])

    print("=== 5 分钟观察（每 30 秒检查一次）===")
    for minute in range(1, 11):
        time.sleep(30)
        check_log(f"t+{minute * 0.5:.0f}m")

    print("=== 最终统计 ===")
    check_log("final")
    with open(LOG, encoding="utf-8", errors="replace") as f:
        lines = f.readlines()
    for line in lines[-8:]:
        if "region-tick" in line:
            print(line.strip())


if __name__ == "__main__":
    main()
