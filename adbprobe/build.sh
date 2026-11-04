#!/usr/bin/env bash
# 编译 AdbProbe.apk（无 res，manifest-only 链接 + 注入 classes.dex）
set -e

SDK="D:/Android/sdk"
BT="$SDK/build-tools/33.0.1"
PLATFORM="$SDK/platforms/android-33/android.jar"
export JAVA_HOME="D:/Java/jdk-17.0.12+7"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/dex assets

# assets 由三个源产出：asx 的 dex、车机侧 killvds.sh、仪表端 ARM 二进制（每次构建都重取）
cp ../asx/build/dex/classes.dex assets/vd.dex
cp ../tools/remote/killvds.sh assets/killvds.sh

# 仪表端接收程序：源码在 tools/icrecv2（含 //go:embed assets/*.png 三张卡片图），
# 交叉编译成 ARMv7 静态 ELF。没有工具链时退回用已有产物。
ICBIN=../tools/icrecv2/icrecv_dbg
if command -v go >/dev/null 2>&1; then
  ( cd ../tools/icrecv2 && GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
      go build -trimpath -ldflags "-s -w" -o icrecv_dbg . )
fi
if [ -f "$ICBIN" ]; then
  cp "$ICBIN" assets/icrecv_dbg
  echo "    仪表端二进制 $(stat -c %s assets/icrecv_dbg) B"
else
  echo "!!  没有 $ICBIN，APK 将不含仪表端程序（只能靠车机 /sdcard 旁路）"
fi

echo "[1/6] aapt2 link (manifest + assets)"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 \
  --target-sdk-version 28

echo "[2/6] javac"
# 用 classpath 而不是 bootclasspath：bootclasspath 下 jdk 的 java.lang.invoke 会缺失，
# lambda（invokedynamic）编译不过；用 classpath 后由 d8 负责 desugar。
"$JAVAC" -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM" \
  -encoding UTF-8 \
  -d build/classes \
  $(find src -name "*.java")

echo "[3/6] d8"
"$JAVA_HOME/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output build/dex \
  $(find build/classes -name "*.class")

echo "[4/6] inject classes.dex"
python - <<'EOF'
import zipfile, shutil, os
shutil.copy("build/base.apk", "build/unsigned.apk")
with zipfile.ZipFile("build/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write("build/dex/classes.dex", "classes.dex")
print("classes.dex injected:", os.path.getsize("build/unsigned.apk"), "bytes")
EOF

echo "[5/6] zipalign"
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true \
  --out AdbProbe.apk build/aligned.apk

ls -la AdbProbe.apk
"$BT/apksigner.bat" verify --verbose AdbProbe.apk | head -6
echo "BUILD OK"
