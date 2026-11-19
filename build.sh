#!/usr/bin/env bash
# 轻传 — 无 Gradle 直接构建脚本（aapt2 + javac + d8 + apksigner）
set -euo pipefail

SDK=${ANDROID_HOME:-/opt/android-sdk}
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
ROOT=$(cd "$(dirname "$0")" && pwd)
SRC="$ROOT/app/src/main"
OUT="$ROOT/build"

# 版本号优先级：环境变量 > git tag / 提交数 > VERSION 文件 > 默认值
FILE_VER=""
FILE_CODE=""
if [ -f "$ROOT/VERSION" ]; then
  FILE_VER=$(sed -n '1p' "$ROOT/VERSION" | tr -d '[:space:]')
  FILE_CODE=$(sed -n '2p' "$ROOT/VERSION" | tr -d '[:space:]')
fi

if [ -z "${VERSION_NAME:-}" ]; then
  VERSION_NAME=$(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)
fi
VERSION_NAME=${VERSION_NAME:-${FILE_VER:-1.0.0}}

if [ -z "${VERSION_CODE:-}" ]; then
  VERSION_CODE=$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || true)
fi
VERSION_CODE=${VERSION_CODE:-${FILE_CODE:-1}}

KEYSTORE="$ROOT/keystore/lightdrop.jks"
KS_PASS="${KEYSTORE_PASS:-lightdrop2024}"
KS_ALIAS="lightdrop"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled/res.zip"

echo "[2/6] aapt2 link"
# 把版本号写进清单副本（aapt2 link 的 --version-* 不会覆盖清单里的显式值）
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/compiled/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 23 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "[3/6] javac"
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -nowarn -source 8 -target 8 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --min-api 23 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=LightDrop, OU=Personal, O=LightDrop, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/LightDrop-$VERSION_NAME.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/LightDrop-$VERSION_NAME.apk" | head -5
ls -lh "$OUT/LightDrop-$VERSION_NAME.apk"
