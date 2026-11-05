#!/usr/bin/env bash
# 编译 仪表投屏 APK（manifest + res 图标 + 注入 classes.dex）
# 包名 com.xiaopeng.vdcast，安装只能走图形安装器（本 ROM 拒绝 adb install 非平台签名包）
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

# assets 由三个源产出：asx 的 dex、车机侧 killvds.sh、仪表端 ARM 二进制
# dex 优先用 asx 的最新构建产物；没构建过就沿用仓库里已入库的那份（= 上次构建结果），
# 避免「只改 APK 界面」时被强制要求先跑 asx/build.sh。
if [ -f ../asx/build/dex/classes.dex ]; then
  cp ../asx/build/dex/classes.dex assets/vd.dex
  echo "    vd.dex 取自 asx/build（$(stat -c %s assets/vd.dex) B）"
else
  echo "!!  未找到 asx/build/dex/classes.dex，沿用 assets/vd.dex（$(stat -c %s assets/vd.dex) B）"
  echo "    改了 asx/ 源码就必须先跑 bash asx/build.sh，否则车里跑的还是旧 dex"
fi
cp ../tools/remote/killvds.sh assets/killvds.sh

# 仪表端接收程序：源码在 tools/icrecv2（含 //go:embed assets/*.png 三张卡片图），
# 交叉编译成 ARMv7 静态 ELF。没有工具链时退回用已有产物。
ICBIN=../tools/icrecv2/icrecv_dbg
# 只改 APK 界面时不需要重编仪表端；本地 Go 版本与提交产物不同会白白改掉二进制 md5，
# 进而触发一次 10~30s 的仪表重投。要重编就 unset SKIP_ICRECV 或显式跑 tools/icrecv2/build.sh
if [ -z "${SKIP_ICRECV:-}" ] && command -v go >/dev/null 2>&1; then
  ( cd ../tools/icrecv2 && GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0 \
      go build -trimpath -ldflags "-s -w" -o icrecv_dbg . )
fi
if [ -f "$ICBIN" ]; then
  cp "$ICBIN" assets/icrecv_dbg
  echo "    仪表端二进制 $(stat -c %s assets/icrecv_dbg) B"
else
  echo "!!  没有 $ICBIN，APK 将不含仪表端程序（只能靠车机 /sdcard 旁路）"
fi

echo "[1/7] aapt2 compile (res)"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/7] aapt2 link (manifest + res + assets)"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  -R build/res.zip \
  --min-sdk-version 26 \
  --target-sdk-version 28

echo "[3/7] javac"
# 用 classpath 而不是 bootclasspath：bootclasspath 下 jdk 的 java.lang.invoke 会缺失，
# lambda（invokedynamic）编译不过；用 classpath 后由 d8 负责 desugar。
"$JAVAC" -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM" \
  -encoding UTF-8 \
  -d build/classes \
  $(find src -name "*.java")

echo "[4/7] d8"
"$JAVA_HOME/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output build/dex \
  $(find build/classes -name "*.class")

echo "[5/7] inject classes.dex"
python - <<'EOF'
import zipfile, shutil, os
shutil.copy("build/base.apk", "build/unsigned.apk")
with zipfile.ZipFile("build/unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write("build/dex/classes.dex", "classes.dex")
print("classes.dex injected:", os.path.getsize("build/unsigned.apk"), "bytes")
EOF

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[7/7] sign"
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
