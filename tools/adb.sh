#!/bin/bash
# adb 包装：每次调用先确保守护进程与连接就绪，失败自动重连重试
# 用法: adb.sh <adb 子命令...>   例: adb.sh shell "pm list packages"
#
# 注意：调用方环境（如本仓库的自动化）会在两次调用之间回收 adb 守护进程，
#       所以每次都得重新 start-server + connect；connect 有一定概率超时
#       （10060，车机 adbd 上还留着一条已死的会话），必须多重试几次。
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ADB="D:/Android/sdk/platform-tools/adb.exe"
DEV="192.168.31.210:5050"

"$ADB" start-server >/dev/null 2>&1

dev_ok() { "$ADB" devices 2>/dev/null | tr -d '\r' | grep -q "^$DEV[[:space:]]*device$"; }

ensure() {
  dev_ok && return 0
  local i
  for i in 1 2 3; do
    "$ADB" connect "$DEV" >/dev/null 2>&1
    local j
    for j in 1 2 3 4 5 6 7 8 9 10; do
      dev_ok && return 0
      sleep 0.4
    done
  done
  return 1
}

# 断连类错误：先重建守护进程再重试
RETRY_RE='no devices/emulators found|device .* not found|device offline|connection reset|broken pipe|cannot connect|Connection refused|protocol fault|failed to get feature set'

for attempt in 1 2 3 4 5; do
  ensure || true
  OUT=$("$ADB" "$@" 2>&1)
  RC=$?
  if printf '%s' "$OUT" | grep -qE "$RETRY_RE"; then
    if [ $attempt -lt 5 ]; then
      "$ADB" kill-server >/dev/null 2>&1
      "$ADB" start-server >/dev/null 2>&1
      sleep 1
      continue
    fi
  fi
  [ -n "$OUT" ] && printf '%s\n' "$OUT"
  exit $RC
done
