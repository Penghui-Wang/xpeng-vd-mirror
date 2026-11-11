#!/bin/bash
# 通用安装：小鹏系统拦截 pm install（isPlatSigned:false），必须走图形安装器 + 键盘导航
# 用法: installapk.sh <本地apk> <包名> [设备内文件名]
#
# 实测的三个坑：
#  1) 安装器是异步起的，sleep 固定秒数经常还没起来就按键，按键全打在别的窗口上
#     → 先轮询 topResumedActivity，确认 packageinstaller 真的在前台
#  2) 两页的默认焦点不同：「继续」页焦点在「继续」，直接 ENTER；
#     「要更新此应用吗」页焦点在「取消」，必须先 RIGHT 再 ENTER。
#     装过一次之后第一页可能不再出现，盲按 ENTER 会把对话框按掉
#     → 用 uiautomator dump 判断当前是哪一页，再选按键序列
#  3) 按键由 PC 一条条发时，adb over WiFi 在按键瞬间掉线，对话框会随连接中断消失
#     → 按键序列整体写成车机侧脚本，setsid 后台一次跑完，PC 只轮询 lastUpdateTime
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
APK="${1:?apk path}"
PKG="${2:?package name}"
NAME="${3:-install.apk}"
REMOTE="/data/local/tmp/$NAME"
DL="/sdcard/Download/$NAME"

A() { bash "$HERE/adb.sh" "$@"; }

read_time() { A shell "dumpsys package $PKG 2>/dev/null | grep lastUpdateTime" | tr -d '\r'; }

before=$(read_time)
if [ -z "$before" ]; then
  # adb 掉线时 before 为空，后面会把「旧时间」误判成安装成功，先重读几次
  for i in 1 2 3; do
    sleep 2
    before=$(read_time)
    [ -n "$before" ] && break
  done
fi
if [ -z "$before" ]; then
  echo "!! 读不到 $PKG 的 lastUpdateTime（adb 掉线？）" >&2
  exit 2
fi
echo "当前版本: $before"

echo "=== push ==="
A push "$APK" "$REMOTE" 2>&1 | tail -1

for round in 1 2 3; do
  echo "=== 第 $round 轮：调起安装器 ==="
  # 先把上一轮残留的 InstallSuccess/安装器界面清掉，否则新的 Intent 会被同一个 task 吞掉
  A shell "am force-stop com.android.packageinstaller" >/dev/null 2>&1
  sleep 1
  A shell "cp $REMOTE $DL; am start -n com.android.packageinstaller/.PackageInstallerActivity \
           -a android.intent.action.VIEW -d file://$DL \
           -t application/vnd.android.package-archive" >/dev/null 2>&1

  # 等安装器真正到前台（本 ROM 没有 mCurrentFocus，只能看 topResumedActivity）
  up=0
  for i in $(seq 1 20); do
    sleep 1
    f=$(A shell "dumpsys activity activities 2>/dev/null | grep topResumedActivity | head -1" | tr -d '\r')
    case "$f" in
      *PackageInstallerActivity*) up=1; break ;;
    esac
  done
  if [ "$up" != 1 ]; then
    echo "  安装器没起来（top=${f:-空}），重试"
    continue
  fi
  echo "  安装器已在前台"

  # 判断当前页，选按键序列（第 2 页要先 RIGHT，否则 ENTER 打在「取消」上）
  A shell "uiautomator dump /data/local/tmp/.ui.xml >/dev/null 2>&1" >/dev/null 2>&1
  if A shell "grep -q 要更新 /data/local/tmp/.ui.xml" >/dev/null 2>&1; then
    KEYS="sleep 1; input keyevent 22; input keyevent 66"
    echo "  当前是「要更新此应用吗」页 → RIGHT + ENTER"
  else
    KEYS="sleep 1; input keyevent 66; sleep 3; input keyevent 22; input keyevent 66"
    echo "  当前是「未知来源」页 → ENTER，再 RIGHT + ENTER"
  fi

  # 按键整体丢给车机侧执行：PC 分步发 keyevent 时掉线会把对话框按没
  A shell "printf '#!/system/bin/sh\n$KEYS\n' > /data/local/tmp/.inst_keys.sh; \
           chmod 755 /data/local/tmp/.inst_keys.sh; \
           setsid sh /data/local/tmp/.inst_keys.sh >/dev/null 2>&1 &" >/dev/null 2>&1

  # 轮询安装结果（最多 22s）
  ok=0
  for i in $(seq 1 22); do
    sleep 1
    after=$(read_time)
    if [ -n "$after" ] && [ "$after" != "$before" ]; then ok=1; break; fi
  done
  if [ "$ok" = 1 ]; then
    echo "=== OK 已更新 ==="
    echo "$after"
    A shell "am force-stop com.android.packageinstaller" >/dev/null 2>&1
    A shell "rm -f $DL /data/local/tmp/.inst_keys.sh /data/local/tmp/.ui.xml" >/dev/null 2>&1
    exit 0
  fi
  echo "  这一轮没装上，再试"
done

echo "=== 失败：三轮都没装上 ==="
A shell "dumpsys package $PKG 2>/dev/null | grep -E 'lastUpdateTime|versionName' | head -2"
exit 1
