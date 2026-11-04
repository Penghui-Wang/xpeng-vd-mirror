#!/usr/bin/env bash
# 看一眼投屏两端当前状态（不改动任何东西）
# 用法: bash status.sh
set -u
# 传给 python / adb 的字符串里带 /tmp/... 这类路径，禁止 MSYS 改写
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")" && pwd -W)"

A() { bash "$HERE/adb.sh" "$@"; }
R() { python "$HERE/../probe/icrun.py" "$1" "${2:-8}" "${3:-40}"; }

echo "=== 车机端（采集/发送） ==="
A shell "grep -E 'child start' /data/local/tmp/vdsend.log | tail -1; echo '  --'; grep -E 'VD created|moved to display|shadow display ready|capture null|send FAIL' /data/local/tmp/vdsend.log | tail -3; grep 'fps=' /data/local/tmp/vdsend.log | tail -2" 2>&1 | grep -v '^$'

echo
echo "=== 虚拟屏 / 目标窗口 ==="
A shell "dumpsys display 2>/dev/null | grep -c 'DisplayDeviceInfo{\"asxvd'; am stack list 2>/dev/null | grep -F com.tencent.qqmusicpad | head -1" 2>&1 | grep -v '^$' \
  | sed -e '1s/^/  虚拟屏数: /' -e '2s/^ *//' -e '2s/^/  窗口: /'

echo
echo "=== 仪表端（接收/合成） ==="
R "ps w | grep -F icrecv_dbg | grep -v grep | head -2; echo '  --'; grep -E 'LISTEN|VISIBLE|frames=|IDLE|RESTORE' /tmp/icrecv.log | tail -3" 10 40

echo
echo "=== 车机网口 ==="
A shell "cat /sys/class/net/eth0/speed 2>/dev/null; ip -4 addr show eth0 2>/dev/null | grep -F inet" 2>&1 | tail -2
