#!/bin/bash
# Builds OpenVM without Gradle (used where Gradle/Maven aren't reachable).
# Needs: JDK/javac (or `java -m jdk.compiler/...`), dx, aapt2, zipalign, apksigner, keytool, android.jar ($ANDROID_JAR).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd); cd "$ROOT"
: "${ANDROID_JAR:?set ANDROID_JAR}"
BT=${BT:-/usr/lib/android-sdk/build-tools/debian}
OUT=${OUT:-$ROOT/build-local}; rm -rf "$OUT"; mkdir -p "$OUT"/{j,res,dex}
STUBS=app/libs/compile-stubs.jar
SRC=app/src/main/java
JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"; command -v javac >/dev/null && JAVAC=javac
echo "== java"
$JAVAC -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$STUBS" -d "$OUT/j" $(find $SRC -name '*.java') 2>&1 | grep -v "^Note\|^warning\|^1 warning" || true
echo "== dex"
"$BT/dx" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/j"
echo "== resources"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"
# aapt2 here is old: link against an older platform jar and strip the few attrs it doesn't know (Gradle's build doesn't need this).
RES_JAR=${RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
python3 - "$OUT/AndroidManifest.xml" <<'PY'
import re,sys
s=open('app/src/main/AndroidManifest.xml').read()
s=s.replace('<manifest ','<manifest package="dev.openvm" ',1)
s=re.sub(r'\s*<property [^>]*/>','',s)
s=re.sub(r' android:foregroundServiceType="[^"]*"','',s)
s=re.sub(r'[|](colorMode|density)','',s)
s=re.sub(r' android:requestLegacyExternalStorage="[^"]*"','',s)
s=re.sub(r'\s*<uses-permission android:name="android.permission.(FOREGROUND_SERVICE_[A-Z_]+|POST_NOTIFICATIONS|READ_MEDIA_[A-Z]+|BLUETOOTH_[A-Z]+|USE_FULL_SCREEN_INTENT|SCHEDULE_EXACT_ALARM)"/>','',s) if False else s
open(sys.argv[1],'w').write(s)
PY
"$BT/aapt2" link -I "$RES_JAR" --manifest "$OUT/AndroidManifest.xml" --min-sdk-version 24 --target-sdk-version ${LOCAL_TARGET_SDK:-28} \
  --version-code 12 --version-name 0.5.5 "$OUT/res.zip" -o "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
[ -d app/src/main/jniLibs ] && (cd app/src/main && zip -qr "$OUT/unsigned.apk" jniLibs >/dev/null && cd "$OUT" && mkdir -p x && cd x && rm -rf lib && cp -r "$ROOT/app/src/main/jniLibs" lib && zip -qr "$OUT/unsigned.apk" lib && zip -qd "$OUT/unsigned.apk" "jniLibs/*" >/dev/null)
[ -d app/src/main/assets ] && (cd app/src/main && zip -qr "$OUT/unsigned.apk" assets)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
[ -f "$OUT/debug.keystore" ] || keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android -keypass android -alias debug -dname "CN=OpenVM" -keyalg RSA -keysize 2048 -validity 20000 >/dev/null 2>&1
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$OUT/OpenVM.apk" "$OUT/aligned.apk"
ls -la "$OUT/OpenVM.apk"
