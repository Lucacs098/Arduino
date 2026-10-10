#!/usr/bin/env bash
# Compila e firma l'APK senza Android Studio né Gradle, con i soli strumenti da riga di comando.
#
# Requisiti: JDK 17+, aapt2, d8 (oppure dx / dalvik-exchange), zipalign, apksigner,
#            android.jar della piattaforma API 34 e nanohttpd-2.3.1.jar.
# Su Ubuntu/Debian: sudo apt install aapt zipalign apksigner dalvik-exchange
#
# Uso:
#   ANDROID_JAR=/percorso/android-34/android.jar NANOHTTPD_JAR=/percorso/nanohttpd-2.3.1.jar ./build-apk.sh
#
# Risultato: apk/BancoFiches.apk (firmato con app/bancofiches.keystore).
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:?imposta ANDROID_JAR con il percorso di android.jar (API 34)}
NANOHTTPD_JAR=${NANOHTTPD_JAR:?imposta NANOHTTPD_JAR con il percorso di nanohttpd-2.3.1.jar}

APP=app/src/main
OUT=build/manual
PACKAGE=it.bancofiches
VERSION_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
VERSION_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
MIN_SDK=$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)
TARGET_SDK=$(sed -n 's/.*targetSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts)

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" apk

echo "» Risorse (aapt2)"
# Con Gradle il package arriva da "namespace"; qui lo aggiungiamo al manifest.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PACKAGE\">|" \
    "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt2 compile --dir "$APP/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -A "$APP/assets" --java "$OUT/gen" "$OUT/res.zip"

echo "» Compilazione Java"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -cp "$NANOHTTPD_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"
(cd "$OUT/classes" && jar cf ../classes.jar .)

echo "» Dex"
if command -v d8 >/dev/null 2>&1; then
    d8 --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar" "$NANOHTTPD_JAR"
else
    DX=$(command -v dx || command -v dalvik-exchange)
    "$DX" --dex --min-sdk-version="$MIN_SDK" --output="$OUT/dex/classes.dex" "$OUT/classes.jar" "$NANOHTTPD_JAR"
fi

echo "» Pacchetto e firma"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks app/bancofiches.keystore --ks-pass pass:bancofiches --key-pass pass:bancofiches \
    --ks-key-alias bancofiches --out apk/BancoFiches.apk "$OUT/aligned.apk"
apksigner verify apk/BancoFiches.apk
rm -f apk/BancoFiches.apk.idsig
echo "✓ apk/BancoFiches.apk ($(du -h apk/BancoFiches.apk | cut -f1))"
