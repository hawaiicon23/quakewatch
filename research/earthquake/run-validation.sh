#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export PATH="/srv/projects/.toolchains/android/prebuilts/jdk/jdk21/linux-x86/bin:$PATH"
python -m unittest discover -s tests -v
quake_out="$(mktemp -d)"
trap 'rm -rf "$quake_out"' EXIT
javac -d "$quake_out" android-app/src/dev/blx/btprobe/{Protocol,QuakeDetector,QuakeConsensus}.java tests/{QuakeEngineTest,QuakeReplay}.java
java -cp "$quake_out" QuakeEngineTest
java -cp "$quake_out" QuakeReplay research/earthquake/data/*.at2 | tee research/earthquake/replay-results.csv
bash android-app/build.sh
