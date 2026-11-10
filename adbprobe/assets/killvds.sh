#!/system/bin/sh
# 杀掉车机上旧的 VdSend 实例（由 startcast.sh 推送到 /data/local/tmp/killvds.sh）
#   killvds.sh        优雅停：先 SIGTERM 让 shutdown hook 把窗口搬回去，3.5 秒后补 SIGKILL
#   killvds.sh check  只打印仍在运行的实例数
#
# 为什么要单独成脚本：`pkill -9 -f com.probe.asx.VdSend` 会把执行它的那个 shell
# 一起干掉——那个 shell 的命令行里就写着这串字符。pkill 按 PID 顺序发信号，
# 常常先杀掉自己，后面的启动命令根本没执行，于是「看起来重启了、其实旧进程还在」。
#
# 这里不仅排除 $$，还要求 /proc/<pid>/cmdline 以 app_process 开头，
# 这样只会命中真正的 app_process 实例，绝不会误伤 adb shell 或父进程。
# 注意：调用方的命令行里不能再出现 "com.probe.asx.VdSend" 字面串，否则父 shell 会被列进来。
MODE="$1"
# 第二个参数是目标包名：恢复时要按它把窗口摆回投屏前所在的左/右 2/3 栏。不传则沿用历史默认值。
RESTORE_PKG="${2:-com.tencent.qqmusicpad}"
n=0

list() {
  ps -A -o PID,ARGS 2>/dev/null | grep -F com.probe.asx.VdSend | awk '{print $1}'
}

# 只数「真的是 VdSend」的实例。
# 注意：不能直接 `list | wc -l` —— 那条 grep 自己的命令行里就写着模式串，
# 会把自己也数进去，轮询永远看到 1（B5 第一版就栽在这）。
count_procs() {
  local_n=0
  for p in $(list); do
    [ -z "$p" ] && continue
    [ "$p" = "$$" ] && continue
    c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' ')
    case "$c" in app_process*) local_n=$((local_n + 1));; esac
  done
  echo "$local_n"
}

if [ "$MODE" = "check" ]; then
  for p in $(list); do
    [ -z "$p" ] && continue
    [ "$p" = "$$" ] && continue
    c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' ')
    case "$c" in app_process*) n=$((n + 1));; esac
  done
  echo "$n"
  exit 0
fi

# 先 TERM 让采集停下来，再等虚拟屏销毁（系统会把 task 自动迁回主屏，但会变成全屏）
nterm=0
for p in $(list); do
  [ -z "$p" ] && continue
  [ "$p" = "$$" ] && continue
  c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' ')
  case "$c" in
    app_process*) kill -TERM "$p" 2>/dev/null && { echo "term $p"; nterm=$((nterm + 1)); };;
  esac
done

# 等旧进程退出 + 虚拟屏销毁 + 系统把窗口迁回主屏
#
# B5：原来是固定 sleep 2。实测 SIGTERM 之后 VdSend 通常几百毫秒就退了，
# 固定等 2 秒纯粹是白等（这段在「换应用 / 停投屏」里每次都要付）。
# 改成轮询进程数：快的时候立刻过，慢的时候上限仍然是 2 秒。
if [ "$nterm" -gt 0 ]; then
  i=0
  while [ $i -lt 20 ]; do
    n=$(count_procs)
    [ "$n" -eq 0 ] && break
    sleep 0.1
    i=$((i+1))
  done
fi

# 关键：恢复不能靠被杀的进程跑 shutdown hook —— 实测 app_process 下的 ART 收到 SIGTERM
# 直接终止，hook 一行都不会执行（vdsend.log 里没有 RESTORE 记录）。改成起一个独立进程
# 做幂等恢复：窗口已在左/右 2/3 栏就什么都不做，被系统迁成全屏才按投屏前的栏位修回去。
if [ -f /data/local/tmp/vd.dex ]; then
  CLASSPATH=/data/local/tmp/vd.dex app_process /system/bin \
    com.probe.asx.VdSend --restore --pkg "$RESTORE_PKG" 2>&1 \
    | grep -E "restore:|pane|bounds|resize" | sed 's/^/  /'
fi

# 还没走的就是挂住了，补刀
for p in $(list); do
  [ -z "$p" ] && continue
  [ "$p" = "$$" ] && continue
  c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\0' ' ')
  case "$c" in
    app_process*) kill -9 "$p" 2>/dev/null && echo "kill9 $p";;
  esac
done
exit 0
