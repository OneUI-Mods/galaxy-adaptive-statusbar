#!/usr/bin/env python3
"""Find the recorded Android device without changing network or debugging settings."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
IDENTITY = ROOT / 'evidence/device/adb-target.json'
ADB = shutil.which('adb') or 'adb'


def run(*args, timeout=5):
    try:
        # Discovery must not consume the input intended for the user's final ADB command.
        result = subprocess.run([ADB, *args], stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, timeout=timeout)
        return result.stdout.strip() if result.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''


def active_devices(text):
    return [line.split()[0] for line in text.splitlines()[1:]
            if len(line.split()) >= 2 and line.split()[1] == 'device']


def mdns_endpoints(text):
    return [m.group(1) for line in text.splitlines()
            if '_adb-tls-connect._tcp' in line
            for m in [re.search(r'(\d+\.\d+\.\d+\.\d+:\d+)\s*$', line)] if m]


def matches(expected, actual):
    # A model/fingerprint alone cannot distinguish two phones of the same model.
    return bool(expected.get('serial')) and all(
        actual.get(key) == expected.get(key) for key in ('serial', 'device', 'fingerprint'))


def properties(endpoint):
    if run('-s', endpoint, 'get-state', timeout=2) != 'device':
        return {}
    keys = [('serial', 'ro.serialno'), ('device', 'ro.product.device'),
            ('model', 'ro.product.model'), ('fingerprint', 'ro.build.fingerprint')]
    return {key: run('-s', endpoint, 'shell', 'getprop', prop, timeout=3) for key, prop in keys}


def remember(endpoint):
    actual = properties(endpoint)
    if not actual.get('serial') or not actual.get('fingerprint'):
        raise RuntimeError('Endpoint is unavailable or does not expose a stable identity.')
    old = json.loads(IDENTITY.read_text()) if IDENTITY.exists() else {}
    if old and not matches(old, actual):
        raise RuntimeError('Refusing to replace the recorded identity with another device.')
    actual['endpoint'] = endpoint
    actual['lan_endpoint'] = old.get('lan_endpoint', endpoint)
    IDENTITY.parent.mkdir(parents=True, exist_ok=True)
    IDENTITY.write_text(json.dumps(actual, indent=2) + '\n')
    IDENTITY.chmod(0o600)


def find_target():
    if not IDENTITY.exists():
        raise RuntimeError('First record the authorized device with --remember HOST:PORT.')
    expected = json.loads(IDENTITY.read_text())
    candidates = [os.environ.get('ANDROID_SERIAL', ''), expected.get('endpoint', '')]
    candidates += active_devices(run('devices'))
    candidates += mdns_endpoints(run('mdns', 'services'))
    candidates += [expected.get('lan_endpoint', '')]
    seen = set()
    for endpoint in candidates:
        if not endpoint or endpoint in seen:
            continue
        seen.add(endpoint)
        actual = properties(endpoint)
        if not matches(expected, actual) and ':' in endpoint:
            run('connect', endpoint, timeout=4)
            actual = properties(endpoint)
        if matches(expected, actual):
            if endpoint != expected.get('endpoint'):
                expected['endpoint'] = endpoint
                IDENTITY.write_text(json.dumps(expected, indent=2) + '\n')
            return endpoint
    raise RuntimeError('Recorded device not found in active ADB connections or LAN mDNS. '
                       'Network/debugging settings were left unchanged. Retry after connectivity returns.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--remember', metavar='HOST:PORT')
    parser.add_argument('--serial', action='store_true', help='Print the verified ADB endpoint.')
    parser.add_argument('adb_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.remember:
        remember(args.remember)
        print('Recorded the authorized device identity.', file=sys.stderr)
        return
    endpoint = find_target()
    command = args.adb_args
    if command and command[0] == '--': command = command[1:]
    if args.serial or not command:
        print(endpoint)
    else:
        result = subprocess.run([ADB, '-s', endpoint, *command])
        raise SystemExit(result.returncode)


if __name__ == '__main__':
    try: main()
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
