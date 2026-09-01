#!/usr/bin/env bash
# pi 工作台 一键构建：aapt → javac → d8 → aapt 打包 → apksigner
# 兼容两种环境：Termux 本机（工具在 PATH）/ GitHub CI（ANDROID_HOME）
set -e
cd "$(dirname "$0")"

if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
  AAPT="$BT/aapt"
  APKSIGNER="$BT/apksigner"
  D8="$BT/d8"
  AJ=$(ls "$ANDROID_HOME"/platforms/*/android.jar | sort -V | tail -1)
else
  AAPT=aapt; APKSIGNER=apksigner; D8=d8
  AJ="${ANDROID_JAR:-$HOME/PiBridge/android.jar}"
fi
echo "== 工具: aapt=$(command -v "$AAPT" 2>/dev/null || echo "$AAPT") AJ=$AJ"

rm -rf gen obj build
mkdir -p gen obj build

# 1) 资源编译 + R.java
"$AAPT" package -f -m -J gen -M AndroidManifest.xml -S res -I "$AJ"

# 2) Java 编译（javac 8 语法，d8 负责脱糖）
javac -source 8 -target 8 -nowarn -Xlint:-options -cp "$AJ" -d obj $(find src gen -name "*.java")

# 3) dex
"$D8" --min-api 24 --release --lib "$AJ" --output build $(find obj -name "*.class")

# 4) 打包 + 塞 dex
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F build/base.apk
(cd build && "$AAPT" add base.apk classes.dex)

# 5) 签名（CI 从 secrets 解出；本机无则自动生成）
if [ ! -f pibridge.keystore ]; then
  keytool -genkeypair -keystore pibridge.keystore -alias pibridge -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass pibridge -keypass pibridge \
    -dname "CN=PiBridge" >/dev/null 2>&1
fi
"$APKSIGNER" sign --min-sdk-version 24 --ks pibridge.keystore \
  --ks-pass pass:pibridge --key-pass pass:pibridge \
  --out build/pibridge.apk build/base.apk

echo "✅ build/pibridge.apk $(stat -c%s build/pibridge.apk) bytes"
