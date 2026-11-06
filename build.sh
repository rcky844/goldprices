#!/usr/bin/env bash
#
# Builds Gold Monitor with the plain Android SDK tools (no Gradle, no AndroidX).
#
#   ./build.sh            build build/goldmonitor.apk
#   ./build.sh install    build, then install on the connected device
#   ./build.sh run        build, install and launch
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-/home/ricky/Android/Sdk}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-36}"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
ADB="${ADB:-$SDK/platform-tools/adb}"
JAVA_HOME_8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"
JAVA="$JAVA_HOME_8/bin/java"
JAVAC="$JAVA_HOME_8/bin/javac"
JAR="$JAVA_HOME_8/bin/jar"
KEYTOOL="$JAVA_HOME_8/bin/keytool"

AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2}"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

BUILD="$ROOT/build"
APK="$BUILD/goldmonitor.apk"
KEYSTORE="$ROOT/keys/goldmonitor.jks"
KS_PASS="goldmonitor"
KS_ALIAS="goldmonitor"
PACKAGE="com.goldmonitor"
ACTIVITY="$PACKAGE/.MainActivity"

for tool in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" \
        "$JAVAC" "$JAR" "$KEYTOOL"; do
    if [ ! -e "$tool" ]; then
        echo "error: missing required tool: $tool" >&2
        exit 1
    fi
done

echo "==> cleaning"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/res"

echo "==> compiling resources"
"$AAPT2" compile --dir res -o "$BUILD/res/resources.zip"

echo "==> linking resources and manifest"
"$AAPT2" link \
    -o "$BUILD/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    "$BUILD/res/resources.zip" \
    -A assets \
    --java "$BUILD/gen" \
    --min-sdk-version 14 \
    --target-sdk-version 24 \
    --version-code 1 \
    --version-name 1.0 \
    --no-auto-version

echo "==> compiling Java"
# JDK 8 is used on purpose: d8 30.0.3 cannot read newer class files.
find src -name '*.java' > "$BUILD/sources.txt"
find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
if ! "$JAVAC" \
    -source 1.7 -target 1.7 \
    -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ROOT/libs/*" \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt"; then
    echo "error: Java compilation failed" >&2
    exit 1
fi

"$JAR" cf "$BUILD/classes.jar" -C "$BUILD/classes" .

echo "==> dexing (application + Bouncy Castle TLS)"
"$D8" \
    --release \
    --min-api 14 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" \
    "$ROOT/libs/bcprov-jdk15on-1.70.jar" \
    "$ROOT/libs/bcutil-jdk15on-1.70.jar" \
    "$ROOT/libs/bctls-jdk15on-1.70.jar"

if [ ! -f "$BUILD/dex/classes.dex" ]; then
    echo "error: d8 did not produce classes.dex" >&2
    exit 1
fi
if [ -f "$BUILD/dex/classes2.dex" ]; then
    echo "error: application does not fit one dex; Dalvik would not load it" >&2
    exit 1
fi

echo "==> packaging"
(cd "$BUILD/dex" && zip -q "$BUILD/app-unsigned.apk" classes.dex)
"$ZIPALIGN" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "==> signing"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass "$KS_PASS" \
        -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 \
        -sigalg SHA1withRSA \
        -validity 10000 \
        -dname "CN=Gold Monitor, O=Gold Monitor, C=US" >/dev/null 2>&1
fi

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" \
    --min-sdk-version 14 \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --out "$APK" \
    "$BUILD/app-aligned.apk"

"$APKSIGNER" verify --min-sdk-version 14 --verbose "$APK" \
    | grep -E 'Verified using|Number of signers' || true

echo
echo "Built: $APK ($(du -h "$APK" | cut -f1))"
echo "Min SDK 14, target SDK 24, v1+v2 signed."

if [ "${1:-}" = "install" ] || [ "${1:-}" = "run" ]; then
    "$ADB" install -r "$APK"
    if [ "${1:-}" = "run" ]; then
        "$ADB" shell am start -n "$ACTIVITY"
    fi
fi
