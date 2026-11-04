#!/usr/bin/env bash
# 车机 adb 包装：每次调用前确保连接，用于本机网络抖动频繁的情况
D=192.168.31.210:5050

ax() {
  local i
  for i in 1 2 3 4; do
    if adb devices 2>/dev/null | grep -q "^$D[[:space:]]*device"; then
      adb -s "$D" "$@"
      return $?
    fi
    adb connect "$D" >/dev/null 2>&1
    sleep 1
  done
  echo "AX: device unreachable" >&2
  return 1
}

axs() { ax shell "$@"; }
