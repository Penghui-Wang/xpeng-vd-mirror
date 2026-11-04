#!/bin/bash
# 通用安装：小鹏系统拦截 pm install（isPlatSigned:false），必须走图形安装器 + 键盘导航
# 用法: installapk.sh <本地apk> <包名> [设备内文件名]
#
# 实测的两个坑：
#  1) 安装器是异步起的，sleep 固定秒数经常还没起来就按键，按键全打在别的窗口上
#     → 改成轮询 mCurrentFocus，确认 packageinstaller 真的在前台再按键
#  2) 「继续」页默认焦点就在「继续」→ ENTER 即可；
#     「要更新此应用吗」页默认焦点在「取消」→ 必须先 RIGHT 再 ENTER
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
APK="${1:?apk path}"
PKG="${2:?package name}"
NAME="${3:-install.apk}"
REMOTE="/data/local/tmp/$NAME"
DL="/sdcard/Download/$NAME"

A() { bash "$HERE/adb.sh" "$@"; }

before=$(A shell "dumpsys package $PKG 2>/dev/null | grep lastUpdateTime" | tr -d '\r')

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

  # 第 1 屏（取消 / 继续）→ 默认焦点在「继续」
  A shell "input keyevent 66" >/dev/null
  sleep 3
  # 第 2 屏（取消 / 更新|安装）→ 默认焦点在「取消」，右移一格再确认
  A shell "input keyevent 22; input keyevent 66" >/dev/null
  sleep 7

  after=$(A shell "dumpsys package $PKG 2>/dev/null | grep lastUpdateTime" | tr -d '\r')
  if [ -n "$after" ] && [ "$after" != "$before" ]; then
    echo "=== OK 已更新 ==="
    echo "$after"
    A shell "am force-stop com.android.packageinstaller" >/dev/null 2>&1
    A shell "rm -f $DL" >/dev/null 2>&1
    exit 0
  fi
  echo "  这一轮没装上，再试"
done

echo "=== 失败：三轮都没装上 ==="
A shell "dumpsys package $PKG 2>/dev/null | grep -E 'lastUpdateTime|versionName' | head -2"
exit 1
