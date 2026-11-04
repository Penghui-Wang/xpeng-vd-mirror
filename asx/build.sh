#!/usr/bin/env bash
# 编译 asx 下的 app_process 工具，产出 /tmp 侧 dex 并推到设备
set -e
SDK="D:/Android/sdk"
BT="$SDK/build-tools/33.0.1"
PLATFORM="$SDK/platforms/android-33/android.jar"
export JAVA_HOME="D:/Java/jdk-17.0.12+7"
JAVAC="$JAVA_HOME/bin/javac.exe"
cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes build/dex

echo "[1/3] javac"
"$JAVAC" -source 8 -target 8 -nowarn \
  -bootclasspath "$PLATFORM" -encoding UTF-8 \
  -d build/classes $(find src -name "*.java")

echo "[2/3] d8"
"$JAVA_HOME/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --release \
  --output build/dex $(find build/classes -name "*.class")

ls -la build/dex/classes.dex
echo "BUILD OK"
