#!/usr/bin/env bash
set -euo pipefail
exec bash "$(dirname "$0")/linux-app/run.sh" "$@"
