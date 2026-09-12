#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.1.0}"
PLATFORM_VERSION="${PLATFORM_VERSION:-36}"

if [[ -z "$SDK_DIR" ]]; then
  echo "ANDROID_HOME oder ANDROID_SDK_ROOT muss gesetzt sein." >&2
  exit 1
fi

TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_DIR/platforms/android-$PLATFORM_VERSION/android.jar"
BUILD="$PROJECT_DIR/.build"
GEN="$BUILD/generated"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"
OUT="$PROJECT_DIR/app/build/outputs/apk/debug"

for required in "$TOOLS/aapt2" "$TOOLS/d8" "$TOOLS/zipalign" "$TOOLS/apksigner" "$ANDROID_JAR"; do
  if [[ ! -e "$required" ]]; then
    echo "Android-Builddatei fehlt: $required" >&2
    exit 1
  fi
done

mkdir -p "$BUILD" "$GEN" "$CLASSES" "$DEX" "$OUT"
find "$GEN" "$CLASSES" "$DEX" -mindepth 1 -delete

"$TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$BUILD/resources.zip"
"$TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$GEN" \
  --min-sdk-version 33 \
  --target-sdk-version 36 \
  --version-code 22 \
  --version-name 2.2.0 \
  -o "$BUILD/unsigned-res.apk" \
  "$BUILD/resources.zip"

find "$PROJECT_DIR/app/src/main/java" "$GEN" -name '*.java' -print > "$BUILD/sources.txt"
java -m jdk.compiler/com.sun.tools.javac.Main \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -encoding UTF-8 \
  -d "$CLASSES" \
  @"$BUILD/sources.txt"

find "$CLASSES" -name '*.class' -print > "$BUILD/classes.txt"
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 33 --output "$DEX" @"$BUILD/classes.txt"

cp "$BUILD/unsigned-res.apk" "$BUILD/unsigned.apk"
(cd "$DEX" && zip -q -j "$BUILD/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KEYSTORE="$BUILD/ankunft-debug.jks"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias ankunftdebug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AnkunftsAlarm Debug,O=Development,C=DE" >/dev/null 2>&1
fi

APK="$OUT/AnkunftsAlarm-v2.2.0-debug.apk"
"$TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias ankunftdebug \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$APK" \
  "$BUILD/aligned.apk"

"$TOOLS/apksigner" verify --verbose --print-certs "$APK"
echo "$APK"
