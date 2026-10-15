#!/usr/bin/env python3
"""Generate bundled notices from Gradle's resolved runtime dependencies and local artifacts."""
from pathlib import Path
from zipfile import ZipFile, BadZipFile
import io
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
rows = (ROOT / 'app/build/runtime-dependencies.txt').read_text().splitlines()
license_text = (ROOT / 'app/src/main/assets/legal/APACHE-2.0.txt').read_text()
parts = [
    'Galaxy Adaptive Bar — Open-source notices',
    'Runtime components are used under the Apache License, Version 2.0.\n'
    'Copyright belongs to their respective authors, including Google / The Android Open Source Project, '
    'JetBrains, and the Guava authors. Exact resolved components follow.\n',
    '\n'.join(rows),
    '\nThe Xposed API is a compile-only dependency and is not included in the APK.\n'
    'API origin: https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar\n',
    license_text,
    '\nXposed API source notice (compile-only):\n' + (ROOT / 'app/libs/XPOSED-NOTICE.txt').read_text(),
]
seen = set()

def notices(zip_file, origin):
    for name in zip_file.namelist():
        base = name.rsplit('/', 1)[-1].lower()
        if ('license' in base or 'notice' in base or 'copyright' in base) and name.startswith('META-INF/'):
            try: text = zip_file.read(name).decode('utf-8').strip()
            except (UnicodeError, KeyError): continue
            if text and text not in seen:
                seen.add(text)
                parts.append('\n' + origin + ' / ' + name + '\n' + text)

for coordinate in rows:
    group, module, version = coordinate.split(':')
    if not group.startswith(('androidx.', 'com.google.', 'org.jetbrains')):
        raise SystemExit('Review license for new dependency: ' + coordinate)
    folder = CACHE / group / module / version
    for pom in folder.glob('*/*.pom'):
        doc = ET.parse(pom)
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        for license in doc.findall('./m:licenses/m:license', ns):
            name = license.findtext('m:name', '', ns)
            url = license.findtext('m:url', '', ns)
            if 'apache' not in (name + url).lower():
                raise SystemExit('Review license for ' + coordinate + ': ' + name)
    for file in folder.glob('*/*'):
        if file.suffix not in ('.aar', '.jar') or file.name.endswith('-sources.jar'): continue
        try:
            with ZipFile(file) as archive:
                notices(archive, coordinate)
                if 'classes.jar' in archive.namelist():
                    with ZipFile(io.BytesIO(archive.read('classes.jar'))) as nested:
                        notices(nested, coordinate)
        except BadZipFile: continue
output = ROOT / 'app/src/main/assets/legal/THIRD-PARTY-NOTICES.txt'
output.write_text('\n'.join(line.rstrip() for line in '\n\n'.join(parts).splitlines()).rstrip() + '\n')
print(f'Wrote notices for {len(rows)} runtime components; {len(seen)} distinct embedded notices.')
