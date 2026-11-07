#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
SDK_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
SDK_JAR="$SDK_HOME/platforms/android-34/android.jar"
BT="${BUILD_TOOLS_DIR:-$SDK_HOME/build-tools/34.0.0}"

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$SDK_JAR"; do
    if [ ! -e "$tool" ]; then
        echo "Не найден: $tool" >&2
        exit 1
    fi
done

VC="${VERSION_CODE:-1}"
VN="${VERSION_NAME:-1.0}"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] compile resources"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] link resources"
"$AAPT2" link -o "$OUT/unsigned.apk" -I "$SDK_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version 29 --target-sdk-version 34 \
    --version-code "$VC" --version-name "$VN" \
    --auto-add-overlay

echo "[3/6] javac"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$SDK_JAR" -classpath "$SDK_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --release --lib "$SDK_JAR" --min-api 29 \
    --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package dex"
cp "$OUT/unsigned.apk" "$OUT/withdex.apk"
(cd "$OUT/dex" && zip -q "$OUT/withdex.apk" classes.dex)
"$ZIPALIGN" -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
if [ -n "${KEYSTORE_B64:-}" ]; then
    KS="$ROOT/release.jks"
    printf '%s' "$KEYSTORE_B64" | base64 -d > "$KS"
    KS_PASS="${KEYSTORE_PASSWORD:-formatforge}"
    ALIAS="${KEY_ALIAS:-formatforge}"
    KEY_PASS="${KEY_PASSWORD:-$KS_PASS}"
else
    KS="$ROOT/debug.jks"
    rm -f "$KS"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
    KS_PASS=android
    ALIAS=androiddebugkey
    KEY_PASS=android
fi

"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" --ks-key-alias "$ALIAS" \
    --out "$ROOT/FormatForge.apk" "$OUT/aligned.apk"

rm -f "$ROOT/FormatForge.apk.idsig"
ls -la "$ROOT/FormatForge.apk"
echo "OK"
