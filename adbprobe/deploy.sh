#!/usr/bin/env bash
# 一键：编译 -> 安装 -> 启动面板 -> 回读日志
# 首次运行会在车机弹「允许 USB 调试」，需在车机上点「允许」并勾选「一律允许」
# 用法: bash adbprobe/deploy.sh
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")" && pwd -W)"
PKG=com.probe.adbprobe

A() { bash "$HERE/../tools/adb.sh" "$@"; }

echo "[1/4] 编译"
bash "$HERE/build.sh" | tail -2

echo "[2/4] 安装"
bash "$HERE/../tools/installapk.sh" "$HERE/AdbProbe.apk" "$PKG" adbprobe.apk | tail -3

echo "[3/4] 启动面板"
A shell "am force-stop $PKG; logcat -c; am start -n $PKG/.MainActivity" >/dev/null 2>&1

echo "[4/4] 车机上点「连接」→「启动 vd」；这里每 3 秒回读一次日志（Ctrl+C 退出）"
echo "----------------------------------------------------------------"
LAST=0
for i in $(seq 1 40); do
  sleep 3
  A shell "logcat -d -s ADBPROBE:I" 2>/dev/null | tr -d '\r' > /tmp/_ap_full.log || true
  TOTAL=$(grep -c ADBPROBE /tmp/_ap_full.log 2>/dev/null || echo 0)
  if [ "${TOTAL:-0}" -gt "$LAST" ]; then
    sed -n "$((LAST + 1)),${TOTAL}p" /tmp/_ap_full.log | sed 's/.*ADBPROBE *: //'
    LAST=$TOTAL
  fi
done
