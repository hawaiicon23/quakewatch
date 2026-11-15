#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -d /srv/projects/.toolchains/android/prebuilts/jdk/jdk21/linux-x86 ]]; then
  export JAVA_HOME=/srv/projects/.toolchains/android/prebuilts/jdk/jdk21/linux-x86
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.local/share/btprobe-build/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.local/share/btprobe-build/android-sdk"
fi
if [[ ! -f .keys/test.jks ]]; then
  echo 'Existing signing key missing: restore .keys/test.jks to preserve update compatibility.' >&2
  exit 1
fi
./gradlew --no-daemon assembleRelease lintRelease "$@"
mkdir -p dist
cp build/outputs/apk/release/BTProbe-release.apk dist/bt-probe.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose dist/bt-probe.apk
sha256sum dist/bt-probe.apk
