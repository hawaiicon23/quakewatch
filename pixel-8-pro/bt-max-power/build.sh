#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${AOSP_ROOT:-}" ]]; then
    if [[ -d /srv/projects/.toolchains/android ]]; then
        AOSP_ROOT=/srv/projects/.toolchains/android
    else
        AOSP_ROOT=/home/blx/Projects/Android/source
    fi
fi
export JAVA_HOME="${JAVA_HOME:-$AOSP_ROOT/prebuilts/jdk/jdk21/linux-x86}"
export PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$AOSP_ROOT/prebuilts/sdk/tools/linux"
ANDROID_JAR="$AOSP_ROOT/prebuilts/sdk/35/public/android.jar"
OUT="$PWD/build"
mkdir -p "$OUT/classes" "$OUT/dex" "$PWD/dist" "$PWD/.keys"
"$TOOLS/bin/aapt2" compile --dir res -o "$OUT/resources.zip"
"$TOOLS/bin/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --version-code 1 --version-name 0.1-max "$OUT/resources.zip"
javac --release 8 -cp "$ANDROID_JAR" -d "$OUT/classes" src/dev/blx/btprobe/maxpower/*.java
jar --create --file "$OUT/classes.jar" -C "$OUT/classes" .
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
"$TOOLS/bin/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [[ ! -f .keys/test.jks ]]; then
    keytool -genkeypair -keystore .keys/test.jks -storepass android -keypass android -alias btprobe \
        -dname "CN=BT Probe Local Test" -keyalg RSA -keysize 2048 -validity 3650 -noprompt
fi
java -jar "$TOOLS/lib/apksigner.jar" sign --ks .keys/test.jks --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias btprobe --out dist/bt-probe-max.apk "$OUT/aligned.apk"
java -jar "$TOOLS/lib/apksigner.jar" verify --verbose dist/bt-probe-max.apk
# Some JDK tools move temporary files into place with private modes and a
# different group. Restore shared access for outputs owned by this builder.
PROJECT_GROUP="$(stat -c %G "$PWD")"
BUILDER_UID="$(id -u)"
find "$OUT" "$PWD/dist" "$PWD/.keys" -uid "$BUILDER_UID" -exec chgrp "$PROJECT_GROUP" {} +
find "$OUT" "$PWD/dist" "$PWD/.keys" -uid "$BUILDER_UID" -type f -exec chmod g+rw,o-rwx {} +
find "$OUT" "$PWD/dist" "$PWD/.keys" -uid "$BUILDER_UID" -type d -exec chmod g+srwx,o-rwx {} +
sha256sum dist/bt-probe-max.apk
echo "APK ready: $PWD/dist/bt-probe-max.apk"
