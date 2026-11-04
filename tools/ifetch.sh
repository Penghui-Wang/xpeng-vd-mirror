#!/usr/bin/env bash
# 从仪表拉文件回本地：仪表起一次性 HTTP 服务 -> 车机 wget -> adb pull
# 用法: bash ifetch.sh <仪表路径> <本地路径> [端口]
set -u
ICRPC_DIR="$(cd "$(dirname "$0")/../probe" && pwd -W)"
ADB="D:/Android/sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

REMOTE="${1:?remote path}"
LOCAL="${2:?local path}"
PORT="${3:-8150}"

DEV="192.168.31.210:5050"
ensure_dev() {
  "$ADB" devices 2>/dev/null | grep -q "$DEV[[:space:]]*device" && return 0
  "$ADB" start-server >/dev/null 2>&1
  "$ADB" connect "$DEV" >/dev/null 2>&1
  for _ in 1 2 3 4 5 6 7 8; do
    "$ADB" devices 2>/dev/null | grep -q "$DEV[[:space:]]*device" && return 0
    sleep 0.5
  done
  return 1
}
ensure_dev || { echo "ERR: car not reachable"; exit 1; }

SIZE=$(cd "$ICRPC_DIR" && python icrpc.py "stat -c %s $REMOTE" 2>/dev/null \
       | grep -oE '[0-9]{3,}' | head -1)
if [ -z "$SIZE" ]; then echo "ERR: 无法读取 $REMOTE 大小"; exit 1; fi
echo "remote $REMOTE = $SIZE bytes"

# 仪表侧：一次性 HTTP 服务（setsid 脱离 RPC 会话，2 次重试）
(cd "$ICRPC_DIR" && python icrpc.py \
  "pkill -f 'nc -l -p $PORT' 2>/dev/null; setsid sh -c 'i=0; while [ \$i -lt 2 ]; do { printf \"HTTP/1.0 200 OK\r\nContent-Length: $SIZE\r\nConnection: close\r\n\r\n\"; cat $REMOTE; } | nc -l -p $PORT >/dev/null 2>&1; i=\$((i+1)); done' >/dev/null 2>&1 & sleep 1; echo SERVER_UP" \
  >/dev/null 2>&1)

CAR="/sdcard/Download/.ifetch.bin"
"$ADB" shell "rm -f $CAR; curl -s -o $CAR http://172.20.1.40:$PORT/f 2>&1 | head -3; stat -c %s $CAR" 2>&1 | tr -d "\r" | tail -2

"$ADB" pull "$CAR" "$LOCAL" 2>&1 | tail -1
"$ADB" shell "rm -f $CAR; curl -s -o $CAR http://172.20.1.40:$PORT/f 2>&1 | head -3; stat -c %s $CAR" 2>&1 | tr -d "\r" | tail -2
ls -l "$LOCAL" 2>/dev/null | awk '{print "local size:", $5}'
