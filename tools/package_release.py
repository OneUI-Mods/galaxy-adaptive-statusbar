#!/usr/bin/env python3
"""Build, validate, sign, and package a release APK. Never distributes a debug APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args, capture=False, env=None):
    return subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True,
                          capture_output=capture, text=True, env=env)


def validate(apk, aapt):
    badging = run(aapt, 'dump', 'badging', apk, capture=True).stdout
    if 'application-debuggable' in badging:
        raise RuntimeError('Refusing to distribute a debuggable APK.')
    manifest = run(aapt, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk,
                   capture=True).stdout
    with zipfile.ZipFile(apk) as archive:
        fixture = ('CaptureFixtureActivity' in manifest
                   or any('contrast_fixture' in name for name in archive.namelist())
                   or any(b'CaptureFixtureActivity' in archive.read(name)
                          for name in archive.namelist() if name.endswith('.dex')))
    if fixture:
        raise RuntimeError('Refusing to distribute test fixtures.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-ref', default='HEAD')
    parser.add_argument('--signing-dir', type=Path, default=ROOT / '.signing')
    parser.add_argument('--check-release-apk', type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    build_tools = sdk / 'build-tools/36.0.0'
    if args.check_release_apk:
        validate(args.check_release_apk.resolve(), build_tools / 'aapt2')
        print('Release APK check passed: not debuggable, no test fixtures.')
        return
    signing = args.signing_dir.resolve()
    for name in ('release.keystore', 'password', 'lineage'):
        if not (signing / name).is_file():
            raise RuntimeError(f'Missing signing material: {name}; see docs/releasing.md.')
    # The source download must describe the exact application being built.
    if run('git', 'ls-files', '--others', '--exclude-standard', '--', 'app', capture=True).stdout:
        raise RuntimeError('Commit untracked application files before packaging a release.')
    run('git', 'diff', '--exit-code', args.source_ref, '--', 'app')
    env = dict(os.environ, ANDROID_HOME=str(sdk))
    run(ROOT / 'gradlew', ':app:assembleRelease', env=env)
    output = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    if metadata['variantName'] != 'release' or len(metadata['elements']) != 1:
        raise RuntimeError('Expected one release variant APK.')
    element = metadata['elements'][0]
    version = element['versionName']
    unsigned = output / element['outputFile']
    validate(unsigned, build_tools / 'aapt2')
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    apk = dist / f'GalaxyAdaptiveBar-{version}-release.apk'
    source = dist / f'GalaxyAdaptiveBar-{version.removesuffix("-experimental")}-source.zip'
    sums = dist / f'SHA256SUMS-{version.removesuffix("-experimental")}.txt'
    with tempfile.TemporaryDirectory(prefix='galaxybar-release-') as temp:
        aligned = Path(temp) / 'aligned.apk'
        signed = Path(temp) / 'signed.apk'
        run(build_tools / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned)
        run(build_tools / 'apksigner', 'sign', '--ks', signing / 'release.keystore',
            '--ks-key-alias', 'galaxybar-release', '--ks-pass', f'file:{signing / "password"}',
            '--lineage', signing / 'lineage', '--rotation-min-sdk-version', '33',
            '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false',
            '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
            '--debuggable-apk-permitted', 'false', '--out', signed, aligned)
        run(build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', signed)
        run(build_tools / 'zipalign', '-c', '-P', '16', '4', signed)
        validate(signed, build_tools / 'aapt2')
        shutil.copyfile(signed, apk)
    run('git', 'archive', '--format=zip', '--prefix=galaxy-adaptive-statusbar/',
        f'--output={source}', args.source_ref)
    sums.write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest()
                            + '  ' + path.name + '\n' for path in (apk, source)))
    print(f'Release APK: {apk.name}\nSource: {source.name}\nChecksums: {sums.name}')


if __name__ == '__main__':
    main()
