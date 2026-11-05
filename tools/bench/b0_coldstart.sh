#!/usr/bin/env bash
# B0 冷启动基线采集：每轮「杀进程 → 启动 → 等连接完成」，只读不改行为。
# 用法：bash tools/bench/b0_coldstart.sh [轮数] [起始编号]
#   单次跑多轮在本机沙箱里会被外层超时打断（实测 >1 轮就 SIGTERM），
#   所以推荐一次一轮、用起始编号续写：... 1 1 / ... 1 2 / ...
# 产物：docs/materials/b0/roundN.*（原始输出，已入 .gitignore）
set -u

ADB=${ADB:-D:/Android/sdk/platform-tools/adb.exe}
DEV=${DEV:-192.168.31.210:5050}
PKG=com.xiaopeng.vdcast
ACT=$PKG/.MainActivity
ROUNDS=${1:-5}
FIRST=${2:-1}
LAST=$((FIRST + ROUNDS - 1))
OUT=${OUT:-docs/materials/b0}

mkdir -p "$OUT"
"$ADB" connect "$DEV" >/dev/null 2>&1
sleep 1

for i in $(seq "$FIRST" "$LAST"); do
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 3                     # 让 ActivityManager 把进程真正回收干净
  "$ADB" logcat -c >/dev/null 2>&1

  "$ADB" shell am start -W -n "$ACT" --es act connect > "$OUT/round$i.start.txt" 2>&1
  sleep 6                     # 连接 + 首轮日志落盘

  "$ADB" logcat -d -v threadtime -s ADBPROBE:I > "$OUT/round$i.log.txt" 2>&1
  # events 缓冲区只能按行数取尾部：整段 dump 在这台车机上要跑几分钟，会把调用方拖死
  "$ADB" logcat -b events -d -t 400 | grep -E "am_activity_launch_time|am_proc_start" \
      > "$OUT/round$i.events.txt" 2>&1

  echo "--- round $i ---"
  grep -E "Status|WaitTime|TotalTime|ThisTime" "$OUT/round$i.start.txt"
done

echo "原始产物：$OUT"
