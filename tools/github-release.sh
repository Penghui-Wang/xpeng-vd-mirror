#!/usr/bin/env bash
# 建 GitHub Release 并上传 APK（本机没有 gh CLI，用 REST API）。
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx bash tools/github-release.sh Penghui-Wang/xpeng-vd-mirror v2.2.0 \
#       adbprobe/AdbProbe.apk docs/发行说明-v2.2.md
#
# 说明：
#   - token 只从环境变量读，不落盘、不打印；
#   - 已存在同名 release 时直接报错退出（不覆盖，避免误删别人的发行版）；
#   - APK 以 assets 附件上传，正文取发行说明文件。
set -euo pipefail

REPO="${1:?用法: GITHUB_TOKEN=... bash tools/github-release.sh <owner/repo> <tag> <apk> [说明文件]}"
TAG="${2:?缺 tag，例如 v2.2.0}"
APK="${3:?缺 APK 路径，例如 adbprobe/AdbProbe.apk}"
NOTES="${4:-}"

[ -n "${GITHUB_TOKEN:-}" ] || { echo "!! 需要 GITHUB_TOKEN 环境变量（repo 权限）"; exit 2; }
[ -f "$APK" ] || { echo "!! 找不到 APK: $APK"; exit 2; }

NAME="$(basename "$APK")"
API="https://api.github.com/repos/${REPO}/releases"
AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}" -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28")

body=""
if [ -n "$NOTES" ] && [ -f "$NOTES" ]; then
    body=$(python - "$NOTES" <<'EOF'
import json, sys
print(json.dumps({"body": open(sys.argv[1], encoding="utf-8").read()}))
EOF
)
fi

echo "=== 1/3 检查是否已有同名 release"
code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "${API}/tags/${TAG}")
if [ "$code" = "200" ]; then
    echo "!! ${TAG} 已经有 release 了，退出（不覆盖）"
    exit 3
fi

echo "=== 2/3 创建 release ${TAG}"
payload=$(python - "$TAG" "$NAME" "$body" <<'EOF'
import json, sys
tag, name, extra = sys.argv[1], sys.argv[2], sys.argv[3]
d = {"tag": tag}
if extra:
    d.update(json.loads(extra))
else:
    d["body"] = ""
print(json.dumps(d))
EOF
)
resp=$(curl -s -X POST "${AUTH[@]}" -H "Content-Type: application/json" \
       -d "$payload" "${API}")
url=$(printf '%s' "$resp" | python -c 'import json,sys; print(json.load(sys.stdin).get("upload_url",""))')
html=$(printf '%s' "$resp" | python -c 'import json,sys; print(json.load(sys.stdin).get("html_url",""))')
[ -n "$url" ] || { echo "!! 创建失败：$resp"; exit 4; }
upload="${url%%\{*}"
echo "    $html"

echo "=== 3/3 上传 $NAME"
curl -s -X POST "${AUTH[@]}" \
     -H "Content-Type: application/vnd.android.package-archive" \
     --data-binary @"$APK" "${upload}?name=${NAME}" \
  | python -c 'import json,sys; d=json.load(sys.stdin); print("    附件:", d.get("name","?"), d.get("size","?"), "bytes")'

echo "[OK] Release 完成：$html"
