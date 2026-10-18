#!/usr/bin/env bash
set -euo pipefail
TASK_TOOL_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$TASK_TOOL_DIR/.."
SERIAL="$(python3 "$TASK_TOOL_DIR/adb_target.py" --serial)"
APK="${1:-}"
if [[ -z "$APK" ]]; then
  APK="$(
    python3 - << 'PY'
import json
from pathlib import Path
metadata = json.loads(Path('app/build/outputs/apk/release/output-metadata.json').read_text())
print('dist/GalaxyAdaptiveBar-' + metadata['elements'][0]['versionName'] + '-release.apk')
PY
  )"
fi
if [[ ! -f "$APK" ]]; then
  echo 'Build a signed release APK with python3 tools/package_release.py first.' >&2
  exit 1
fi
adb -s "$SERIAL" install -r "$APK"
adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli scope add dev.ryuya.galaxybar com.android.systemui/0"'
adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli scope add dev.ryuya.galaxybar com.sec.android.app.launcher/0"'
adb -s "$SERIAL" shell 'su -c "/data/adb/lspd/cli modules enable dev.ryuya.galaxybar"'
adb -s "$SERIAL" shell am start -n dev.ryuya.galaxybar/.MainActivity
echo 'Installed. SystemUI must restart once for the hook to load. Color processing defaults to OFF.'
