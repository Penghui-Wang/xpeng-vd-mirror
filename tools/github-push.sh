#!/usr/bin/env bash
# 把当前仓库推到 GitHub。
#
# 背景：这台机器上 github.com:443 大约 1/3 概率超时（丢包型），
# 但 github.com:22 的 SSH 是 4/4 稳定，所以一律走 SSH，并做两轮兜底：
#   第 1~3 轮 → github.com:22
#   第 4~5 轮 → ssh.github.com:443（22 端口被掐时用）
#
# 用法:
#   bash tools/github-push.sh <用户名>/<仓库名> [分支]
# 例:
#   bash tools/github-push.sh zhangsan/car-vd
set -uo pipefail

REMOTE_SPEC="${1:-}"
BRANCH="${2:-$(git rev-parse --abbrev-ref HEAD)}"

if [ -z "$REMOTE_SPEC" ]; then
    echo "用法: bash tools/github-push.sh <用户名>/<仓库名> [分支]"
    echo "例:   bash tools/github-push.sh zhangsan/car-vd"
    exit 2
fi

cd "$(git rev-parse --show-toplevel)" || exit 2

# ---------- 0. 先确认密钥能用 ----------
echo "=== 检查 SSH 认证 ==="
AUTH=$(timeout 15 ssh -o BatchMode=yes -o ConnectTimeout=8 -T git@github.com 2>&1)
if echo "$AUTH" | grep -q "successfully authenticated"; then
    echo "  认证通过：${AUTH#Hi }"
else
    echo "$AUTH" | head -3
    echo
    echo "[!] SSH 还认不出来。先去 https://github.com/settings/keys 添加公钥："
    echo
    cat ~/.ssh/id_ed25519.pub
    echo
    exit 3
fi

# ---------- 1. 配 remote ----------
URL="git@github.com:${REMOTE_SPEC}.git"
if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$URL"
    echo "=== 已更新 origin -> $URL"
else
    git remote add origin "$URL"
    echo "=== 已添加 origin -> $URL"
fi

# ---------- 2. 推，带两轮兜底 ----------
push_once() {   # $1 = 远端名或 "host:路径" 写法
    GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=10" \
        git push "$1" "HEAD:refs/heads/${BRANCH}" 2>&1
}

for round in 1 2 3 4 5; do
    case "$round" in
        1|2|3) TARGET=origin ; WHERE="22 端口" ;;
        4|5)   TARGET="github-443:${REMOTE_SPEC}.git" ; WHERE="443 端口" ;;
    esac
    echo
    echo "=== 第 ${round}/5 轮：$TARGET（$WHERE）→ $BRANCH"
    OUT=$(push_once "$TARGET")
    echo "$OUT" | tail -8
    if echo "$OUT" | grep -qE "new branch|up to date|->"; then
        # 用 origin 推成功的才设 upstream，避免 upstream 变成一个 URL
        git fetch origin "$BRANCH" >/dev/null 2>&1
        git branch --set-upstream-to="origin/${BRANCH}" >/dev/null 2>&1
        echo
        echo "[OK] 推送完成：https://github.com/${REMOTE_SPEC}"
        exit 0
    fi
    sleep 3
done

echo
echo "[X] 5 轮都失败了。可能是："
echo "    1. 仓库还没在 GitHub 上创建（SSH 不能建库，必须先在网页建一个空库）"
echo "    2. 公钥没加到账号里 / 加到了别的账号"
echo "    3. 账号没有该仓库的写权限"
exit 1
