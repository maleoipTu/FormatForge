#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK=/opt/android/platforms/android-34/android.jar
R8=/tmp/r8.jar
OUT="$ROOT/build"
APP="$ROOT/app"
NAME="FormatForge"

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] compile resources"
aapt2 compile --dir "$APP/res" -o "$OUT/res.zip"

echo "[2/6] link resources"
aapt2 link -o "$OUT/unsigned.apk" -I "$SDK" \
    --manifest "$APP/AndroidManifest.xml" \
    -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version 29 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    --auto-add-overlay

echo "[3/6] javac"
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$SDK" -classpath "$SDK" \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8" com.android.tools.r8.D8 --release --lib "$SDK" --min-api 29 \
    --output "$OUT/dex" @"$OUT/classes.txt"

echo "[5/6] package dex"
cp "$OUT/unsigned.apk" "$OUT/withdex.apk"
(cd "$OUT/dex" && zip -q "$OUT/withdex.apk" classes.dex)
zipalign -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
apksigner sign --ks "$ROOT/keystore.jks" --ks-pass pass:formatforge \
    --key-pass pass:formatforge --ks-key-alias formatforge \
    --out "$ROOT/$NAME.apk" "$OUT/aligned.apk"

ls -la "$ROOT/$NAME.apk"
echo "OK"
