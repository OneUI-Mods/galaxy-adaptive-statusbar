#!/usr/bin/env bash
set -euo pipefail
TASK_TOOL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="$(python3 "$TASK_TOOL_DIR/adb_target.py" --serial)"
# Live stop; works without root and without restarting SystemUI.
adb -s "$SERIAL" shell am start -n dev.ryuya.galaxybar/.MainActivity --ez disable true
if [[ "${1:-}" == "--unhook" ]]; then
    adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli modules disable dev.ryuya.galaxybar"'
    adb -s "$SERIAL" shell 'su -c "kill -TERM $(pidof com.android.systemui)"'
fi
