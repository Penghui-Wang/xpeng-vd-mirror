#!/usr/bin/env bash
# 部署：车机 /sdcard/Download/<src>  ->  仪表 <dst>
# 用法: bash deploy.sh <车机文件名> <仪表目标路径>
set -u
ADB="D:/Android/sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
SRC="${1:-icrecv2}"
DST="${2:-/tmp/icrecv}"
PORT="${3:-8101}"

SIZE=$($ADB shell "stat -c %s /sdcard/Download/$SRC" 2>/dev/null | tr -d '\r')
if [ -z "$SIZE" ] || ! [ "$SIZE" -gt 1000 ] 2>/dev/null; then
  echo "ERR: 车机上 /sdcard/Download/$SRC 不可用 (size='$SIZE')"
  exit 1
fi
echo "serving $SRC ($SIZE bytes) on :$PORT"

$ADB shell "F=/sdcard/Download/$SRC; S=$SIZE; i=0; while [ \$i -lt 6 ]; do { printf 'HTTP/1.0 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n' \$S; cat \$F; } | nc -l -p $PORT -q 2 >/dev/null 2>&1; i=\$((i+1)); done" \
  >/tmp/httpd_$$.log 2>&1 &
ADBJPID=$!
sleep 2

cd "$(dirname "$0")/../probe" || exit 1
python icrpc.py "rm -f $DST; S=\$(date +%s%N); wget -q -O $DST http://172.20.1.45:$PORT/$SRC; E=\$(date +%s%N); echo took_\$(( (E-S)/1000000 ))_ms; ls -l $DST | awk '{print \$5}'; md5sum $DST; chmod +x $DST" 2>&1 \
  | grep -vE "^\.\.\.\.|hex\(前160\)|^[0-9a-f]{2} " | tail -6

kill $ADBJPID 2>/dev/null
echo "local md5: $(md5sum "$(dirname "$0")/icrecv2/icrecv_arm" | cut -d' ' -f1)"
