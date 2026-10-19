#!/bin/bash
# Build the Hermes phone app without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Output: build/hermes-mobile.apk (signed with a local key in ~/.android/hermes-mobile.keystore, or $KEYSTORE).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
# JAVA_HOME only if it really exists; otherwise use the java/javac on PATH (CI sets it via setup-java)
if [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ]; then export PATH="$JAVA_HOME/bin:$PATH"
elif [ -d /opt/homebrew/opt/openjdk@17/bin ]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"; fi
command -v javac >/dev/null || { echo "javac not found: install a JDK 17+ or set JAVA_HOME"; exit 1; }
PLATFORM=35          # targetSdk
COMPILE_PLATFORM=36  # compile against Android 16 APIs (Live Updates)
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-$COMPILE_PLATFORM/android.jar"
VERSION_NAME="${VERSION_NAME:-$(cat ../VERSION)}"
VERSION_CODE="${VERSION_CODE:-1}"
KS="${KEYSTORE:-$HOME/.android/hermes-mobile.keystore}"  # CI passes the release key here

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR (sdkmanager \"platforms;android-$COMPILE_PLATFORM\")"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "missing build-tools 35.0.0"; exit 1; }

# 1. web UI → assets
( cd ../web && npx vite build >/dev/null )
mkdir -p assets/www
cp ../web/dist/index.html assets/www/index.html
# the phone-side helper ships inside the app: the app writes it into Debian itself, no plugin redeploy needed
cp ../hermes-plugin/hermes-mobile/services.py assets/services.py

rm -rf build && mkdir -p build/gen build/classes build/dex

# 2. resources + manifest
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version "$PLATFORM" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java build/gen -o build/unsigned.apk build/res.zip

# 3. Java → classes → dex
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
( cd build/dex && zip -q -j ../unsigned.apk classes.dex )

# 4. align + sign
mkdir -p "$(dirname "$KS")"   # a fresh CI machine has no ~/.android
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass hermesmobile -keypass hermesmobile \
  -alias hermes -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Hermes Mobile, O=omarqaterge" >/dev/null 2>&1
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:hermesmobile --key-pass pass:hermesmobile \
  --out build/hermes-mobile.apk build/aligned.apk
"$BT/apksigner" verify build/hermes-mobile.apk
ls -la build/hermes-mobile.apk
