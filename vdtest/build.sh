#!/usr/bin/env bash
set -e

SDK="D:/Android/sdk"
BT="$SDK/build-tools/33.0.1"
PLATFORM="$SDK/platforms/android-33/android.jar"
export JAVA_HOME="D:/Java/jdk-17.0.12+7"
JAVAC="$JAVA_HOME/bin/javac.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/dex

echo "[1/6] aapt2 link (manifest only, no res)"
"$BT/aapt2.exe" link \
  -o build/base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 31

echo "[2/6] javac"
"$JAVAC" -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATFORM" \
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
  --out VDTest.apk build/aligned.apk

ls -la VDTest.apk
"$BT/apksigner.bat" verify --verbose VDTest.apk | head -6
echo "BUILD OK"
