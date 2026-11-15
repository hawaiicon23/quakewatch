#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# -eq 0 ]]; then
    exec python3 bt_probe.py --gui
else
    exec python3 bt_probe.py "$@"
fi
