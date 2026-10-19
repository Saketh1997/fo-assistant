#!/usr/bin/env bash
# Builds build/fo-assist.apk with the bare SDK tools (no Gradle): aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/36.0.0
JAR=$SDK/platforms/android-36/android.jar
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex"
"$BT/aapt2" compile --dir res -o "$OUT/res"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 36 --version-code 11 --version-name 1.6.1 "$OUT"/res/*.flat
# default bridge address comes from the untracked bridge.url (empty = set it in the app's settings)
URL=$(cat bridge.url 2>/dev/null | tr -d '[:space:]' || true)
mkdir -p "$OUT/gen"
printf 'package org.sakethmetta.foassist;\nfinal class BuildConfig {\n    static final String BRIDGE_URL = "%s";\n}\n' "$URL" > "$OUT/gen/BuildConfig.java"
javac --release 17 -Xlint:-options -Xlint:deprecation -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java') "$OUT/gen/BuildConfig.java"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'a').write(sys.argv[2],'classes.dex')" "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# one local signing key, kept outside build/ so reinstalls upgrade in place
KS=keystore.jks
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass fo-assist -keypass fo-assist -alias fo \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Fo Assist" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:fo-assist --ks-key-alias fo --out "$OUT/fo-assist.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/fo-assist.apk"
ls -la "$OUT/fo-assist.apk"
