#!/usr/bin/env bash
set -euo pipefail
TASK_TOOL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$TASK_TOOL_DIR/.."
SERIAL="$(python3 "$TASK_TOOL_DIR/adb_target.py" --serial)"
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli scope add dev.ryuya.galaxybar com.android.systemui/0"'
adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli modules enable dev.ryuya.galaxybar"'
adb -s "$SERIAL" shell am start -n dev.ryuya.galaxybar/.MainActivity
echo 'Installed. SystemUI must restart once for the hook to load. Color processing defaults to OFF.'
