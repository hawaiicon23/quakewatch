#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
    echo 'Usage: bash pixel-5/install.sh [USB_SERIAL]'
    echo 'Installs the Android APK on a connected Pixel 5 and opens BT Probe.'
    echo 'Run on the laptop where the Pixel is plugged in. Build on Arch first.'
    exit 0
fi
if [[ $# -gt 1 ]]; then
    echo 'Expected at most one USB serial number.' >&2
    exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK_PATH:-$ROOT/android-app/dist/bt-probe.apk}"
if [[ ! -f "$APK" ]]; then
    echo "APK missing: $APK. Build android-app/build.sh on Arch and copy the APK here." >&2
    exit 1
fi
if [[ -n "${1:-${ANDROID_SERIAL:-}}" ]]; then
    ADB=(adb -s "${1:-${ANDROID_SERIAL:-}}")
else
    ADB=(adb -d)
fi
DEVICE="$("${ADB[@]}" shell getprop ro.product.device | tr -d '\r\n')"
if [[ "$DEVICE" != redfin ]]; then
    echo "Expected Pixel 5 (redfin); received '$DEVICE'. No APK installed." >&2
    exit 1
fi
"${ADB[@]}" install -r "$APK"
"${ADB[@]}" shell am start -n dev.blx.btprobe/.MainActivity
