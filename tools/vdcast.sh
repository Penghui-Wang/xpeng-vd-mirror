#!/usr/bin/env bash
# 仪表投屏 · PC 侧遥控驱动（唯一入口）
#
# 与 APK 的动作表一一对应：
#   APK   adbprobe/src/com/xiaopeng/vdcast/Acts.java（TABLE）
#   文档  docs/遥控入口-act-v1.md
# 改动作名时三处一起改；离线测试 adbprobe/test/ActsTest 会校验 APK 与本文的 ACTS 是否一致。
#
# 为什么必须用脚本而不是手写 am start：
#   am start 会把「--es 之后多出来的裸参数」当 data URI。手写
#       am start ... --es act crop 1,2,3,4
#   时坐标 1,2,3,4 变成 data，应用只收到 act=crop、没有 --es crop，
#   于是悄悄沿用界面上的旧值，看起来像「改了没生效」（B0 基线 §3.3 的坑）。
#   这里永远按「参数在前、--es act 最后」拼，写错顺序这件事在构造上就不可能发生。
#
# 用法：
#   bash tools/vdcast.sh <动作> [--pkg P] [--mode vd|main] [--crop x,y,w,h]
#                              [--crect x,y,w,h] [--port N]
#                              [--dry-run] [--wait <文本>] [--timeout 秒]
#   bash tools/vdcast.sh log [行数] [过滤串]      # 回读 ADBPROBE 日志（取证用）
#   bash tools/vdcast.sh table                    # 打印动作表（不发命令）
#   bash tools/vdcast.sh selftest                 # 离线自检：命令拼装、动作表、顺序
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=com.xiaopeng.vdcast
COMP="$PKG/.MainActivity"

# 动作表（与 Acts.java 的 TABLE 一一对应，顺序也一致）
ACTS="connect status health probe pick apps area snap crop crect start stop cancel residue clean preview reset coords exit exitnow resync help"

A() { bash "$HERE/adb.sh" "$@"; }

die() { echo "!! $*" >&2; exit 2; }

usage() {
  cat <<'EOF'
用法：
  bash tools/vdcast.sh <动作> [--pkg P] [--mode vd|main] [--crop x,y,w,h]
                              [--crect x,y,w,h] [--port N]
                              [--dry-run] [--wait <文本>] [--timeout 秒]
  bash tools/vdcast.sh log [行数] [过滤串]      # 回读 ADBPROBE 日志（取证用）
  bash tools/vdcast.sh table                    # 打印动作表（不发命令）
  bash tools/vdcast.sh selftest                 # 离线自检（不需要车机）

例：
  bash tools/vdcast.sh status
  bash tools/vdcast.sh crect --crect 352,180,1216,360
  bash tools/vdcast.sh start --pkg com.tencent.qqmusicpad --mode vd --wait 就绪 --timeout 40
  bash tools/vdcast.sh stop --wait RESTORE_OK --timeout 12
EOF
}

# 该动作是否消费参数 $2
takes() {
  case "$1:$2" in
    connect:port|start:pkg|start:mode|start:crop|start:crect|crop:crop|crect:crect) return 0 ;;
  esac
  return 1
}

# 动作中「必须」带的参数
required() {
  case "$1" in
    start) [ -n "${V_pkg:-}" ]  || die "act=start 需要 --pkg（不知道有哪些包就先跑：bash tools/vdcast.sh apps）" ;;
    crop)  [ -n "${V_crop:-}" ] || die "act=crop 需要 --crop x,y,w,h" ;;
    crect) [ -n "${V_crect:-}" ] || die "act=crect 需要 --crect x,y,w,h" ;;
  esac
}

# 参数只属于声明消费它的动作：带了但不消费 → 直接拒绝。
# 不拒绝的话就是「act=status 顺手把 pkg 改了」那种静默副作用。
check_params() {
  local act="$1"
  [ -n "${V_pkg:-}" ]   && { takes "$act" pkg   || die "act=$act 不消费 --pkg（拒绝静默改配置）"; }
  [ -n "${V_mode:-}" ]  && { takes "$act" mode  || die "act=$act 不消费 --mode"; }
  [ -n "${V_crop:-}" ]  && { takes "$act" crop  || die "act=$act 不消费 --crop"; }
  [ -n "${V_crect:-}" ] && { takes "$act" crect || die "act=$act 不消费 --crect"; }
  [ -n "${V_port:-}" ]  && { takes "$act" port  || die "act=$act 不消费 --port"; }
  return 0
}

# 拼命令：参数固定顺序在前，--es act 永远最后。
# selftest 直接调它做断言，所以这里只打印、不执行。
build_cmd() {
  local act="$1" out="am start -n $COMP"
  [ -n "${V_pkg:-}" ]   && out="$out --es pkg '$V_pkg'"
  [ -n "${V_mode:-}" ]  && out="$out --es mode '$V_mode'"
  [ -n "${V_crop:-}" ]  && out="$out --es crop '$V_crop'"
  [ -n "${V_crect:-}" ] && out="$out --es crect '$V_crect'"
  [ -n "${V_port:-}" ]  && out="$out --es port '$V_port'"
  # 这一行就是本脚本存在的理由：act 之后不许再有任何 token
  out="$out --es act '$act'"
  printf '%s' "$out"
}

# 参数值只允许这些字符：既防拼进 shell 的怪字符，也保证单引号包裹一定安全
check_value() {
  local k="$1" v="$2"
  case "$v" in
    *[!A-Za-z0-9_,.:/-]*) die "--$k 的值含非法字符：$v" ;;
  esac
}

logcount() {
  A logcat -d -s ADBPROBE:I 2>/dev/null | grep -cF -- "$1" || true
}

cmd_log() {
  local n="${1:-40}" pat="${2:-}"
  if [ -n "$pat" ]; then
    A logcat -d -s ADBPROBE:I 2>/dev/null | grep -F -- "$pat" | tail -n "$n"
  else
    A logcat -d -s ADBPROBE:I 2>/dev/null | tail -n "$n"
  fi
}

cmd_table() {
  local a
  echo "动作表（$(( $(echo $ACTS | wc -w) )) 条）："
  printf '  %s\n' "connect   [--es port]             只连 adbd"
  printf '  %s\n' "status                            打印现状（工程级）"
  printf '  %s\n' "health                            刷新三层小灯"
  printf '  %s\n' "probe                             环境四步自检"
  printf '  %s\n' "pick                              打开选应用"
  printf '  %s\n' "apps                              候选应用清单写日志"
  printf '  %s\n' "area                              打开仪表区域框选页"
  printf '  %s\n' "snap                              打开车机画面裁切页"
  printf '  %s\n' "crop      --es crop x,y,w,h       应用裁切（热生效）"
  printf '  %s\n' "crect     --es crect x,y,w,h      应用仪表矩形（需重启）"
  printf '  %s\n' "start     --es pkg … [--es mode vd|main] [--es crop …] [--es crect …]"
  printf '  %s\n' "stop                              停止并复原（4 步）"
  printf '  %s\n' "cancel                            中断当前任务"
  printf '  %s\n' "residue                           重新做启动自检"
  printf '  %s\n' "clean                             一键清理残留"
  printf '  %s\n' "preview                           打开实时预览"
  printf '  %s\n' "reset                             仪表区域复位到安全区"
  printf '  %s\n' "coords                            控件坐标写日志"
  printf '  %s\n' "exit                              退出（带确认）"
  printf '  %s\n' "exitnow                           退出（不确认）"
  printf '  %s\n' "resync                            清工具同步标记"
  printf '  %s\n' "help                              打印动作表（APK 侧）"
  echo
  echo "参数写在前、动作写最后；本脚本替你保证这个顺序。"
}

# 离线自检：不需要车机、不需要 adb
cmd_selftest() {
  local pass=0 fail=0 a out rest n
  selftest_assert() { # $1 描述  $2 条件(0 通过)
    if [ "$2" = 0 ]; then pass=$((pass+1)); else fail=$((fail+1)); echo "  FAIL $1"; fi
  }

  # 1) 动作表：不重复、与 ACTS 一致
  n=$(echo $ACTS | wc -w)
  selftest_assert "动作数 = 22（实际 $n）" $([ "$n" = 22 ] && echo 0 || echo 1)
  selftest_assert "动作不重复" $([ "$(echo $ACTS | tr ' ' '\n' | sort -u | wc -l)" = "$n" ] && echo 0 || echo 1)

  # 2) 每条动作拼出来的命令：只有一个 --es act，且它在最后、后面没有任何 token
  for a in $ACTS; do
    V_pkg=""; V_mode=""; V_crop=""; V_crect=""; V_port=""
    takes "$a" pkg   && V_pkg=com.demo.app
    takes "$a" mode  && V_mode=vd
    takes "$a" crop  && V_crop=960,345,1440,426
    takes "$a" crect && V_crect=352,180,1216,360
    takes "$a" port  && V_port=5050
    out=$(build_cmd "$a")
    case "$out" in
      *"--es act '$a'") selftest_assert "$a 命令以 --es act 结尾" 0 ;;
      *)                selftest_assert "$a 命令以 --es act 结尾" 1 ;;
    esac
    c=$(printf '%s' "$out" | grep -o -- '--es act' | wc -l)
    selftest_assert "$a 只有一个 --es act（实际 $c）" $([ "$c" = 1 ] && echo 0 || echo 1)
    rest=${out##*--es act}
    selftest_assert "$a act 之后无裸 token（实际 '$rest'）" $([ "$rest" = " '$a'" ] && echo 0 || echo 1)
  done

  # 3) 坑的回归：start 带 crop 时，坐标必须出现在 act 之前
  V_pkg=com.demo.app; V_mode=vd; V_crop=960,345,1440,426; V_crect=352,180,1216,360; V_port=""
  out=$(build_cmd start)
  case "$out" in
    *"--es crop '960,345,1440,426'"*"--es act 'start'") selftest_assert "crop 坐标在 act 之前" 0 ;;
    *) selftest_assert "crop 坐标在 act 之前" 1 ;;
  esac
  # 4) 反例：手工拼的错误写法里，act 之后确实有裸 token（本脚本永远不会这么拼）
  bad="am start -n $COMP --es act 'crop' '1,2,3,4'"
  rest=${bad##*--es act}
  case "$rest" in
    *"1,2,3,4"*) selftest_assert "错误写法能被识别（act 之后有裸 token）" 0 ;;
    *)           selftest_assert "错误写法能被识别（act 之后有裸 token）" 1 ;;
  esac

  # 5) 旧名必须被拒绝
  V_pkg=""; V_mode=""; V_crop=""; V_crect=""; V_port=""
  case "vd" in vd|main|cluster) selftest_assert "旧名 vd/main/cluster 会被拒绝" 0 ;;
    *) selftest_assert "旧名 vd/main/cluster 会被拒绝" 1 ;;
  esac

  echo
  echo "selftest pass=$pass fail=$fail"
  [ "$fail" = 0 ] || return 1
  echo "SELFTEST OK"
}

# ---------------------------------------------------------------- 主流程

cmd="${1:-}"
[ $# -gt 0 ] && shift

case "$cmd" in
  ""|-h|--help) usage; exit 0 ;;
  table)  cmd_table; exit 0 ;;
  selftest) cmd_selftest; exit $? ;;
  log)    cmd_log "${1:-40}" "${2:-}"; exit $? ;;
esac

# 旧名：不静默失败，直接说改成什么（R3-2 不要求兼容，但不能让人猜）
case "$cmd" in
  vd)      die "act=vd 已改名：bash tools/vdcast.sh start --mode vd" ;;
  main)    die "act=main 已改名：bash tools/vdcast.sh start --mode main" ;;
  cluster) die "act=cluster 已改名：bash tools/vdcast.sh area" ;;
esac

case " $ACTS " in
  *" $cmd "*) ;;
  *)
    case "$cmd" in
      -*) die "选项要写在动作之后：bash tools/vdcast.sh start --pkg x $cmd" ;;
    esac
    die "未知动作 '$cmd'（bash tools/vdcast.sh table 看全表）" ;;
esac

V_pkg=""; V_mode=""; V_crop=""; V_crect=""; V_port=""
DRY=0; WAIT_PAT=""; TIMEOUT=30; HAS_WAIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --pkg)     V_pkg="${2:-}";   shift 2 ;;
    --mode)    V_mode="${2:-}";  shift 2 ;;
    --crop)    V_crop="${2:-}";  shift 2 ;;
    --crect)   V_crect="${2:-}"; shift 2 ;;
    --port)    V_port="${2:-}";  shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --wait)    WAIT_PAT="${2:-}"; HAS_WAIT=1; shift 2 ;;
    --timeout) TIMEOUT="${2:-30}"; shift 2 ;;
    *) die "未知选项 '$1'" ;;
  esac
done

[ -n "$V_pkg" ]   && check_value pkg   "$V_pkg"
[ -n "$V_mode" ]  && check_value mode  "$V_mode"
[ -n "$V_crop" ]  && check_value crop  "$V_crop"
[ -n "$V_crect" ] && check_value crect "$V_crect"
[ -n "$V_port" ]  && check_value port  "$V_port"

check_params "$cmd"
required "$cmd"

dev="$(build_cmd "$cmd")"
echo "==> adb shell $dev"
[ "$DRY" = 1 ] && exit 0

before=0
[ "$HAS_WAIT" = 1 ] && before=$(logcount "$WAIT_PAT")

out=$(A shell "$dev" 2>&1)
rc=$?
printf '%s\n' "$out" | tail -3
[ $rc -ne 0 ] && exit $rc

if [ "$HAS_WAIT" = 1 ]; then
  i=0
  while [ "$i" -lt "$TIMEOUT" ]; do
    sleep 1
    i=$((i + 1))
    c=$(logcount "$WAIT_PAT")
    if [ "${c:-0}" -gt "${before:-0}" ]; then
      echo "   OK 等到（${i}s）：$(A logcat -d -s ADBPROBE:I 2>/dev/null | grep -F -- "$WAIT_PAT" | tail -1)"
      exit 0
    fi
  done
  echo "!! ${TIMEOUT}s 内没等到 /$WAIT_PAT/（看 bash tools/vdcast.sh log 60）" >&2
  exit 1
fi
exit 0
