#!/usr/bin/env bash
set -euo pipefail
TASK_TOOL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$TASK_TOOL_DIR/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
mkdir -p build/probe/dex
"${KOTLINC:-kotlinc}" -jvm-target 17 -classpath "$SDK/platforms/android-37.0/android.jar" \
  -include-runtime -d build/probe/classes.jar tools/CaptureProbe.kt
"$SDK/build-tools/37.0.0/d8" --min-api 35 --lib "$SDK/platforms/android-37.0/android.jar" \
  --output build/probe/dex build/probe/classes.jar
(cd build/probe/dex && zip -q ../probe.jar classes.dex)
if [[ "${1:-}" == --build-only ]]; then
  exit 0
fi
SERIAL="$(python3 "$TASK_TOOL_DIR/adb_target.py" --serial)"
adb -s "$SERIAL" push build/probe/probe.jar /data/local/tmp/galaxy-capture-probe.jar
CAPTURE_KIND="${1:-display}"
MIRROR_DELAY="${2:-0}"
[[ "$CAPTURE_KIND" == layers || "$CAPTURE_KIND" == display ]]
[[ "$MIRROR_DELAY" =~ ^[0-9]+$ ]]
adb -s "$SERIAL" shell "su -c 'CLASSPATH=/data/local/tmp/galaxy-capture-probe.jar app_process /system/bin CaptureProbe /data/local/tmp/galaxy-probe.png $CAPTURE_KIND $MIRROR_DELAY'"
adb -s "$SERIAL" pull /data/local/tmp/galaxy-probe.png evidence/device/probe-with-statusbar.png
