#!/usr/bin/env bash
# 一次性把车机上的文件投送到仪表（车机 HTTP 服务 + 仪表 wget，同一进程内完成）
# 用法: bash ideploy.sh <车机文件> <仪表目标路径> [端口]
set -u
HERE="$(cd "$(dirname "$0")" && pwd -W)"
ADB="D:/Android/sdk/platform-tools/adb.exe"
DEV="192.168.31.210:5050"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

SRC="${1:?car path}"
DST="${2:?instrument path}"
PORT="${3:-8105}"

# adb 守护进程会被周期性回收，每次调用前必须重建连接
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

SIZE=$("$ADB" shell "stat -c %s $SRC" 2>/dev/null | tr -d '\r' | grep -oE '^[0-9]+')
[ -z "$SIZE" ] && { echo "ERR: $SRC missing on car"; exit 1; }
echo "src=$SRC size=$SIZE -> $DST  (port $PORT)"

# 车机侧循环 HTTP 服务（8 次），与本次脚本同生命周期
# -q 是「stdin 结束后再等几秒才关连接」，不是超时。原来给 2 秒，
# 3.4MB 的二进制在慢链路上只发出去 60KB 就被切断（仪表侧只拿到 61440 B）。
"$ADB" shell "i=0; while [ \$i -lt 8 ]; do { printf 'HTTP/1.0 200 OK\r\nContent-Length: $SIZE\r\nConnection: close\r\n\r\n'; cat $SRC; } | nc -l -p $PORT -q 60 >/dev/null 2>&1; i=\$((i+1)); done" >/dev/null 2>&1 &
SRVPID=$!
sleep 2

cd "$HERE/../probe" || exit 1
python - "$DST" "$PORT" <<'PY'
import sys, icrpc
dst, port = sys.argv[1], sys.argv[2]
cmd = (f"rm -f {dst}; S=$(date +%s); "
       f"wget -q -O {dst} http://172.20.1.45:{port}/x; "
       "E=$(date +%s); echo took_$((E-S))s; "
       f"ls -l {dst} 2>&1 | tail -1; chmod +x {dst} 2>/dev/null; md5sum {dst} 2>&1")
_, out, _ = icrpc.run(cmd, timeout=180, hold=90)
txt = out.decode("utf-8", "replace")
print(txt[-400:].strip())
PY

kill $SRVPID 2>/dev/null
wait $SRVPID 2>/dev/null
echo "local md5: $(md5sum "$HERE/../probe/$(basename "$SRC")" 2>/dev/null | cut -d' ' -f1)"
