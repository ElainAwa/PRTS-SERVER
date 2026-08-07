#!/bin/bash
# P3 v05 最终压测：双区域各 150 Invulnerable 羊（AI 保留触发移动），5 分钟观察。
# 用法: bash p3_stress_v05.sh
PY="C:/Users/Soft_/AppData/Local/Programs/Python/Python314/python.exe"
RCON="D:/mc/PRTS-1.21.1/_chk/rcon.py"
LOG="D:/mc/PRTS-1.21.1/server-out-p4a.log"
cd /d/mc/PRTS-1.21.1

echo "=== 清理旧 sheep/item ==="
"$PY" "$RCON" 127.0.0.1 25575 prts123 "kill @e[type=minecraft:sheep]" | head -1
"$PY" "$RCON" 127.0.0.1 25575 prts123 "kill @e[type=minecraft:item]" | head -1

echo "=== 批量 summon（region1 chunk-1 x=-16..-9 + region0 chunk0 x=0..7, y=100 空气, Invulnerable 防摔落）==="
for ((i = 0; i < 150; i++)); do
  x1=$((-16 + i % 8))
  x0=$((i % 8))
  z=$((8 + (i / 8) * 3))
  "$PY" "$RCON" 127.0.0.1 25575 prts123 "summon minecraft:sheep $x1 100 $z {Invulnerable:1b}" > /dev/null
  "$PY" "$RCON" 127.0.0.1 25575 prts123 "summon minecraft:sheep $x0 100 $z {Invulnerable:1b}" > /dev/null
  if [ $((i % 25)) -eq 0 ]; then echo "spawned $((i * 2))/300"; fi
done
echo "=== 300 就位 ==="
"$PY" "$RCON" 127.0.0.1 25575 prts123 "execute if entity @e[type=minecraft:sheep] run say STRESS_300_OK" | head -1

echo "=== 5 分钟观察 ==="
for ((m = 1; m <= 10; m++)); do
  "$PY" -S -c "import time; time.sleep(30)"
  echo "--- t+$((m * 30))s ---"
  grep -cE "FATAL|Index -1|ArrayIndexOutOfBoundsException|Exception in server tick" "$LOG"
  grep "region-tick" "$LOG" | tail -1
done
echo "=== 最终 ==="
grep -cE "FATAL|Index -1|ArrayIndexOutOfBoundsException|Exception in server tick" "$LOG"
grep "region-tick" "$LOG" | tail -1
