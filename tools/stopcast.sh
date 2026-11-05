#!/usr/bin/env bash
# 停止投屏：先停车机发送端，再给仪表端发 SIGTERM 让它自己 restore 回原仪表版面
#
# ⚠ 本脚本是**不经 APK** 的裸机直驱（基准测试 / APK 挂了时的兜底）。
#   正常流程用 APK 的统一入口：bash tools/vdcast.sh stop（走 restore 幂等 + 红色结论）。
#
# 用法: bash stopcast.sh
set -u
# 传给 python / adb 的字符串里带 /tmp/... 这类路径，禁止 MSYS 改写
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")" && pwd -W)"

A() { bash "$HERE/adb.sh" "$@"; }
R() { python "$HERE/../probe/icrun.py" "$1" "${2:-8}" "${3:-40}"; }

echo "[1/3] 停车机发送端"
# 必须用 killvds.sh：`pkill -f com.probe.asx.VdSend` 会先杀掉执行它的 shell 自己
# killvds.sh 现在是 TERM -> 等虚拟屏销毁 -> 起独立进程 --restore 把窗口改回右栏 -> 补 KILL
# （不能靠被杀的进程跑 shutdown hook：app_process 下的 ART 收 SIGTERM 直接终止，hook 不执行）
A shell "sh /data/local/tmp/killvds.sh; rm -f /data/local/tmp/vdcap" 2>&1 | tail -8 | sed 's/^/     /'
echo "     残留进程: $(A shell 'sh /data/local/tmp/killvds.sh check' 2>&1 | tail -1 | tr -d '\r')"
echo "     窗口位置: $(A shell "am stack list 2>/dev/null | grep -F com.tencent.qqmusicpad | head -1" 2>&1 | tail -1 | tr -d '\r' | sed 's/^ *//')"
echo "     残留虚拟屏: $(A shell "dumpsys display | grep -c 'DisplayDeviceInfo{\"asxvd'" 2>&1 | tail -1 | tr -d '\r')"

echo "[2/3] 停仪表接收端（SIGTERM -> restore）"
# 仪表没有 pkill：用 ps -o pid,args + awk 取 PID；模式拆开避免匹配到本 shell
R 'PAT=icrecv_; PAT=${PAT}dbg; for p in $(ps -o pid,args | grep -F "$PAT" | awk "{print \$1}"); do [ "$p" = "$$" ] && continue; kill -TERM "$p" 2>/dev/null; done; sleep 3; ps -o pid,args | grep -F "$PAT" | grep -v grep | wc -l' 12 40 | tail -1 \
  | sed 's/^/     残留进程: /'

echo "[3/3] 确认仪表已恢复原版面"
R "grep -E 'RESTORE_OK|pixels restored|RESTORED blank' /tmp/icrecv.log | tail -2" 8 40 | tail -2

echo "done"
