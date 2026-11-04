#!/usr/bin/env bash
# 把 tools/icrecv2/icrecv_dbg（交叉编译产物）推到车机 /sdcard/Download 并起 HTTP 服务供仪表 wget。
# 用法: tools/push.sh [port]
# 输出最后一行是 "READY <port> <remote-name> <size> <md5>"
set -u
ADB="D:/Android/sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/icrecv2/icrecv_dbg"
PORT="${1:-8140}"

[ -f "$BIN" ] || { echo "missing $BIN"; exit 1; }
NAME="ic$(date +%H%M%S)"
SIZE=$(stat -c %s "$BIN")
MD5=$(md5sum "$BIN" | cut -d' ' -f1)

"$ADB" push "$BIN" "/sdcard/Download/$NAME" >/dev/null 2>&1
GOT=$("$ADB" shell "md5sum /sdcard/Download/$NAME" | tr -d '\r' | cut -d' ' -f1)
[ "$GOT" = "$MD5" ] || { echo "PUSH_MD5_MISMATCH got=$GOT want=$MD5"; exit 2; }

# 停掉旧的服务，避免端口占用
"$ADB" shell "pkill -f 'nc -l -p $PORT' 2>/dev/null; true" >/dev/null 2>&1

"$ADB" shell "F=/sdcard/Download/$NAME; S=$SIZE; i=0; while [ \$i -lt 300 ]; do { printf 'HTTP/1.0 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n' \$S; cat \$F; } | nc -l -p $PORT -q 2 >/dev/null 2>&1; i=\$((i+1)); done" &
SRV=$!
sleep 1
echo "READY $PORT $NAME $SIZE $MD5"
wait $SRV
