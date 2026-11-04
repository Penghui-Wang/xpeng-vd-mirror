#!/usr/bin/env bash
# 交叉编译仪表端接收程序（i.MX6 / Linux 3.x / ARMv7 静态）
#
# 产物 icrecv_dbg 是 ELF32 ARM，无 INTERP、无 DYNAMIC（纯静态），
# 不内嵌任何资源：卡片美术属于自绘 UI 那套，已从本仓库剥离。
# adbprobe/build.sh 会自动调用本脚本，再把产物塞进 APK 的 assets/。
set -e
cd "$(dirname "$0")"

echo "[1/2] go build (GOOS=linux GOARCH=arm GOARM=7, CGO 关闭)"
GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
  go build -trimpath -ldflags "-s -w" -o icrecv_dbg .

echo "[2/2] 校验"
python - <<'EOF'
import struct
b = open("icrecv_dbg", "rb").read()
e_type, e_machine = struct.unpack_from("<HH", b, 16)
e_phoff, = struct.unpack_from("<I", b, 28)
e_phentsize, e_phnum = struct.unpack_from("<HH", b, 42)
kinds = [struct.unpack_from("<I", b, e_phoff + i * e_phentsize)[0] for i in range(e_phnum)]
ok = b[:4] == b"\x7fELF" and b[4] == 1 and e_machine == 40 and e_type == 2
print("  size      :", len(b))
print("  ELF32/ARM :", ok)
print("  static    :", 3 not in kinds and 2 not in kinds)
raise SystemExit(0 if ok else 1)
EOF
echo "BUILD OK: $(pwd)/icrecv_dbg"
