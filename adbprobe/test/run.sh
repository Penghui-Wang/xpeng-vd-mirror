#!/usr/bin/env bash
# 本机（JVM）跑 ADB 客户端的协议验证：不需要车机、不需要 adb
#   ./run.sh
set -e
export JAVA_HOME="D:/Java/jdk-17.0.12+7"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAVA="$JAVA_HOME/bin/java.exe"

cd "$(dirname "$0")"
rm -rf out
mkdir -p out

"$JAVAC" -encoding UTF-8 -nowarn -d out \
  android/util/Base64.java \
  ../src/com/xiaopeng/vdcast/Adb.java \
  ../src/com/xiaopeng/vdcast/Icrpc.java \
  ../src/com/xiaopeng/vdcast/VdCtl.java \
  MockAdbdTest.java \
  CfgTest.java

"$JAVA" -cp out MockAdbdTest
# 投屏区口径断言（口径 B：默认必须落在安全区，全屏仍可用但不默认）
"$JAVA" -cp out CfgTest
