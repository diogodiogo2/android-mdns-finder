#!/bin/sh
# Builds MdnsFinder.apk with the plain Android SDK command-line tools (no Gradle).
#
#   ANDROID_HOME=$HOME/android-sdk ./build.sh
#   ANDROID_JAR=/path/to/android.jar BUILD_TOOLS=/path/to/build-tools/34.0.0 ./build.sh
#
# Needs: JDK 8 or newer, zip, platforms;android-28 and build-tools (d8, aapt2,
# zipalign, apksigner).
set -e
cd "$(dirname "$0")"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-28/android.jar}"
if [ -z "$BUILD_TOOLS" ]; then
    BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
fi

[ -f "$ANDROID_JAR" ] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }
[ -x "$BUILD_TOOLS/d8" ] || { echo "build-tools not found (set BUILD_TOOLS)" >&2; exit 1; }
command -v zip >/dev/null || { echo "'zip' is required" >&2; exit 1; }

rm -rf build
mkdir -p build/classes

# -parameters: d8 8.2.x crashes on classes compiled by JDK 21 without it.
# -encoding UTF-8: Msg.java contains accented characters.
javac -encoding UTF-8 -parameters -source 8 -target 8 -Xlint:-options \
    -cp "$ANDROID_JAR" -d build/classes src/br/local/mdnsfinder/*.java

"$BUILD_TOOLS/d8" --min-api 9 --lib "$ANDROID_JAR" --output build \
    $(find build/classes -name '*.class')

"$BUILD_TOOLS/aapt2" link -o build/base.apk --manifest AndroidManifest.xml \
    -I "$ANDROID_JAR" --min-sdk-version 9 --target-sdk-version 28 \
    --version-code 1 --version-name 1.0
(cd build && zip -q base.apk classes.dex)

"$BUILD_TOOLS/zipalign" -f 4 build/base.apk build/aligned.apk

KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey \
    --out MdnsFinder.apk build/aligned.apk

echo "Built MdnsFinder.apk"
