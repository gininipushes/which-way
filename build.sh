#!/bin/bash
# Gradle-free build: aapt2 + javac + D8 (r8.jar) + zipalign + apksigner
# Env: ANDROID_JAR (platform 34 android.jar), R8_JAR (r8.jar),
#      KEYSTORE (default ./release.keystore, generated if missing), KS_PASS, KS_ALIAS
set -e
cd "$(dirname "$0")"
AJ=${ANDROID_JAR:-/workspace/tools/android.jar}
D8=${R8_JAR:-/workspace/tools/r8.jar}
KS=${KEYSTORE:-release.keystore}; PASS=${KS_PASS:-changeme}; ALIAS=${KS_ALIAS:-disha}
OUT=WhichWay.apk
rm -rf build && mkdir -p build/gen build/classes build/dex
aapt2 compile --dir res -o build/res.zip
aapt2 link -I "$AJ" --manifest AndroidManifest.xml -A assets --java build/gen \
  --min-sdk-version 24 --target-sdk-version 34 -o build/unsigned.apk build/res.zip
javac -nowarn -source 11 -target 11 -encoding UTF-8 -cp "$AJ" -d build/classes $(find src build/gen -name '*.java')
java -cp "$D8" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" --output build/dex $(find build/classes -name '*.class')
cp build/unsigned.apk build/withdex.apk
(cd build/dex && zip -q -j ../withdex.apk classes.dex)
zipalign -f -p 4 build/withdex.apk build/aligned.apk
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=WhichWay" >/dev/null 2>&1
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass pass:"$PASS" --key-pass pass:"$PASS" --out $OUT build/aligned.apk
apksigner verify $OUT && ls -la $OUT
