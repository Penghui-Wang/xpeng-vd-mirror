#!/usr/bin/env bash
# 在车机上起一个单文件 HTTP 服务，供仪表 wget 拉取。
# 用法: tools/serve.sh <本地文件> <远程文件名> [端口]
# 会先清掉占用同一端口的旧 nc，并校验绑定成功后再进入循环。
set -u
ADB="D:/Android/sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

LOCAL="$1"
RNAME="$2"
PORT="${3:-8140}"

[ -f "$LOCAL" ] || { echo "no such file: $LOCAL"; exit 1; }
SIZE=$(stat -c %s "$LOCAL")
MD5=$(md5sum "$LOCAL" | cut -d' ' -f1)
REMOTE="/sdcard/Download/$RNAME"

"$ADB" start-server >/dev/null 2>&1
"$ADB" connect 192.168.31.210:5050 >/dev/null 2>&1
for i in $(seq 1 10); do
  "$ADB" devices 2>/dev/null | grep -q "device$" && break
  sleep 1
done

"$ADB" push "$LOCAL" "$REMOTE" >/dev/null 2>&1 || { echo "PUSH_FAILED"; exit 2; }
GOT=$("$ADB" shell "md5sum $REMOTE" | tr -d '\r' | cut -d' ' -f1)
[ "$GOT" = "$MD5" ] || { echo "PUSH_MD5_MISMATCH got=$GOT want=$MD5"; exit 3; }

# 清掉占用该端口的旧服务
"$ADB" shell "for p in \$(pidof nc); do grep -qa 'nc -l -p $PORT' /proc/\$p/cmdline 2>/dev/null && kill -9 \$p; done; true" >/dev/null 2>&1
sleep 1

echo "SERVING port=$PORT remote=$RNAME size=$SIZE md5=$MD5"
"$ADB" shell "F=$REMOTE; S=$SIZE; i=0; while [ \$i -lt 2000 ]; do { printf 'HTTP/1.0 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n' \$S; cat \$F; } | nc -l -p $PORT -q 2 >/dev/null 2>&1; i=\$((i+1)); done; echo LOOP_END"
