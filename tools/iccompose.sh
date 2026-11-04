#!/usr/bin/env bash
# 在仪表上后台起 compose 接收服务（detached，RPC 会话断开也不死）
# 用法: bash iccompose.sh [port] [hold] [top] [bot] [cards(1/0)]
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
PROBE="$(cd "$(dirname "$0")/../probe" && pwd -W)"
PORT="${1:-5560}"
HOLD="${2:-900}"
TOP="${3:-72}"
BOT="${4:-80}"
CARDS="${5:-1}"
BIN="${BIN:-/tmp/icrecv_k}"

cd "$PROBE" || exit 1
python - "$BIN" "$PORT" "$HOLD" "$TOP" "$BOT" "$CARDS" <<'PY'
import sys, icrpc
b, port, hold, top, bot, cards = sys.argv[1:7]
cmd = (
    "pkill -f 'compose %s' 2>/dev/null; sleep 1; "
    ": > /tmp/compose.log; "
    "setsid %s compose %s %s %s %s %s >> /tmp/compose.log 2>&1 < /dev/null & "
    "sleep 3; tail -6 /tmp/compose.log; echo; ps | grep -c icrecv_"
) % (port, b, port, hold, top, bot, cards)
_, out, _ = icrpc.run(cmd, timeout=60, hold=12)
txt = out.decode("utf-8", "replace")
print("".join(ch if (31 < ord(ch) < 127 or ch in "\r\n\t") else "." for ch in txt)[:1200])
PY
