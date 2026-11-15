#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
COMPILER="${CLANG:-clang}"
if [[ -z "${CLANG:-}" ]] && ! command -v ld.lld >/dev/null; then
    AOSP_CLANG=/home/blx/Projects/Android/source/prebuilts/clang/host/linux-x86/clang-r596125/bin/clang
    if [[ -x "$AOSP_CLANG" ]]; then COMPILER="$AOSP_CLANG"; fi
fi
for SERVICE in 0 70; do
NAME=qrtr-probe
if [[ "$SERVICE" == 70 ]]; then NAME=qrtr-probe-lte; fi
"$COMPILER" --target=aarch64-linux-android26 -fuse-ld=lld -nostdlib -static \
    -O2 -Wall -Wextra -Werror -fno-builtin -fno-stack-protector \
    -Wl,-e,_start -Wl,--build-id=none -DLOOKUP_SERVICE="$SERVICE" src/qrtr_probe.c -o "build/$NAME"
chgrp "$(stat -c %G .)" "build/$NAME"
chmod 660 "build/$NAME"
file "build/$NAME"
done
