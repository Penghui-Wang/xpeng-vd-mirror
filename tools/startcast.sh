#!/usr/bin/env bash
# 一键启动「车机屏幕区域 -> 仪表中间带」投屏
#
# ⚠ 本脚本是**不经 APK** 的裸机直驱（7 步，用于基准测试 / APK 挂了时的兜底）。
#   正常流程请用 APK 的统一入口：bash tools/vdcast.sh start --pkg …（8 步，见 docs/遥控入口-act-v1.md）。
#   两条路径的步骤数刻意不同，不要混着数：APK 多了「启动自检」和「健康快照」。
#
# 用法: bash startcast.sh [main|vd] [fps] [仪表hold秒]
#   main  抓主屏（默认）：车机屏上必须看得到目标窗口，切走仪表跟着变
#   vd    抓虚拟屏：窗口搬到独立虚拟屏，车机屏切走不影响投屏
#   默认 30fps，hold 86400 秒（hold 是「断流超时」，每来一帧就重置，正常投屏不会到期）
#
# 关键约束 1：车机端必须用 shell 域启动（adb shell + setsid）。
#   用售后域（u:r:xpaftersalesd:s0，原 asroot.sh 方案，未包含在本仓库）启动时，
#   SurfaceControl.captureDisplay 约 330 次后恒返回 null、
#   ProcessBuilder 起 screencap 几十次后报 error=13；
#   同样的 dex 在 shell 域下 1500 次零失败。
#
# 关键约束 2：仪表侧 compose 参数是 <port> <hold> <top> <bot> <cards>
#   中间带高度 = 720 - top - bot = 720 - 72 - 80 = 568，与发送端 OUT_H 一致。
#
# 关键约束 3：仪表端没有 pkill/pgrep（只有 busybox ps/kill），
#   且 `pkill -f <类名>` 会连执行它的那个 shell 一起杀（它命令行里就写着这串字符）。
#   这里统一用 ps + awk + kill，并排除自身。
set -u
# 传给 python / adb 的字符串里带 /tmp/... 这类路径，禁止 MSYS 改写
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")" && pwd -W)"
MODE="${MODE:-}"
if [ $# -gt 0 ] && { [ "$1" = "vd" ] || [ "$1" = "main" ]; }; then
  MODE="$1"; shift
fi
MODE="${MODE:-main}"
FPS="${1:-30}"
HOLD="${2:-86400}"
DEX=/data/local/tmp/vd.dex
ICBIN=/tmp/icrecv_dbg
PORT=5560

A() { bash "$HERE/adb.sh" "$@"; }
# R <命令> [hold秒] [timeout秒]   在仪表上执行并只打印可读输出
R() { python "$HERE/../probe/icrun.py" "$1" "${2:-8}" "${3:-40}"; }

echo "[0/7] 检查车机 adb"
if ! A shell 'echo car_ok' 2>/dev/null | tr -d '\r' | grep -q car_ok; then
  echo "!! 车机 192.168.31.210:5050 连不上（没开机 / adb 关闭 / 不在同一网络）"
  exit 1
fi
echo "  ok (mode=$MODE)"

echo "[1/7] 推发送端 dex + 杀进程脚本"
A push "$HERE/../asx/build/dex/classes.dex" "$DEX" >/dev/null 2>&1 || { echo "!! push dex 失败"; exit 1; }
A push "$HERE/remote/killvds.sh" /data/local/tmp/killvds.sh >/dev/null 2>&1
A shell "chmod 755 /data/local/tmp/killvds.sh; rm -f /data/local/tmp/vdcap" >/dev/null 2>&1
echo "  $DEX"

echo "[2/7] 停旧发送端（先 TERM 让 vd 模式把窗口搬回去）"
A shell "sh /data/local/tmp/killvds.sh" 2>&1 | tail -2 | sed 's/^/  /'


echo "[3/7] 确保仪表端 $ICBIN"
# 注意：不能用 grep icrecv_dbg 判断存在——ls 的报错信息里也含这个名字，会永远判成"已存在"
if R "test -x $ICBIN && echo HAVE_IT || echo NO_IT" 6 30 | grep -q HAVE_IT; then
  echo "  已存在"
else
  echo "  仪表上没有，经车机 HTTP 中转推送（约 10~30s）"
  A push "$HERE/icrecv2/icrecv_dbg" /data/local/tmp/icrecv_dbg >/dev/null 2>&1
  bash "$HERE/ideploy.sh" /data/local/tmp/icrecv_dbg "$ICBIN" 8105 | tail -3
fi

echo "[4/7] 起仪表接收端（中间带 y=72..640 = 720-72-80，1920x568）"
# 停旧进程：模式拆成 icrecv_ + dbg 再拼，避免匹配到本 shell 自己
R 'PAT=icrecv_; PAT=${PAT}dbg; for p in $(ps -o pid,args | grep -F "$PAT" | awk "{print \$1}"); do [ "$p" = "$$" ] && continue; kill "$p" 2>/dev/null; done; sleep 2; ps -o pid,args | grep -F "$PAT" | grep -v grep | wc -l' 10 40 | tail -1 | sed 's/^/  残留: /'
# ICBANDS：上下条（从 fb0 拷贝的仪表原条）刷新间隔。每次整幅 flush 要 15~20ms，
# 250ms 一次等于每 5 帧白吃一帧；放宽到 1200ms 实测 22.9fps -> 27fps。
R "cd /tmp && ICBANDS=${ICBANDS:-1200} setsid ./icrecv_dbg compose $PORT $HOLD 72 80 0 > /tmp/icrecv.log 2>&1 & sleep 2; ps -o pid,args | grep -F icrecv | grep -v grep" 10 40 | tail -2

echo "[5/7] 起车机发送端（shell 域，mode=$MODE，$FPS fps）"
# vd 模式要建虚拟屏 + 搬窗口，起来得慢一些（约 5~8s），所以 sleep 给足
WAIT=6
[ "$MODE" = "vd" ] && WAIT=10
A shell "CLASSPATH=$DEX setsid app_process /system/bin \
  com.probe.asx.VdSend --child --mode $MODE 1920 568 $FPS \
  > /data/local/tmp/vdsend.log 2>&1 & sleep $WAIT; \
  grep -E 'child start|VD created|target task|moved to display|pane follow|crop follow pane|shadow display ready|capture ready|args crop|FAIL' \
  /data/local/tmp/vdsend.log | tail -8" 2>&1 | tail -10

echo "[6/7] 等 10 秒看端到端速率"
sleep 10
echo "  --- 车机：采集/发送 ---"
A shell "grep 'fps=' /data/local/tmp/vdsend.log | tail -2" 2>&1 | tail -2
echo "  --- 仪表：接收/合成 ---"
R "grep -E 'frames=|LISTEN|VISIBLE' /tmp/icrecv.log | tail -2" 8 40 | tail -2

echo "[7/7] 健康检查"
A shell "grep -cE 'capture null|SF capture degraded|send FAIL' /data/local/tmp/vdsend.log 2>/dev/null || echo 0" 2>&1 | tail -1 \
  | sed 's/^/  车机异常计数: /'
if [ "$MODE" = "vd" ]; then
  A shell "am stack list 2>/dev/null | grep -A1 'RootTask id=' | grep -F com.tencent.qqmusicpad | head -1" 2>&1 | tail -1 \
    | sed 's/^/  目标窗口位置: /'
fi

echo
echo "调裁切区: echo 'x,y,w,h' > 车机 /data/local/tmp/vdcrop.txt（热生效，无需重启）"
echo "         默认 960,345,1440,426（右栏 [960,0]-[2400,1080] 的横条；窗口在左栏会自动左移）"
echo "停止投屏: bash stopcast.sh（vd 模式会等窗口搬回主屏并还原左/右栏位）"
echo "看状态  : bash status.sh"
