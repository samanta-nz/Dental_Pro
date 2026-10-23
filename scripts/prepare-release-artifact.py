#!/usr/bin/env python3
"""Validate a signed release and create only public distribution artifacts."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

def properties(path):
    return dict(line.split('=', 1) for line in Path(path).read_text().splitlines()
                if '=' in line and not line.lstrip().startswith('#'))

version = properties('gradle.properties')
name, code = version['VERSION_NAME'], int(version['VERSION_CODE'])
if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', name) or code <= 0:
    raise SystemExit('Invalid release version.')
tag = 'v' + name
ref = os.environ.get('GITHUB_REF', '')
if ref.startswith('refs/tags/') and ref != 'refs/tags/' + tag:
    raise SystemExit('Release tag does not match gradle.properties.')
notes = Path(f'docs/RELEASE_{name}_FA.md')
if not notes.is_file():
    raise SystemExit('Version-specific release notes are missing.')
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0'
apk = Path('app/build/outputs/apk/release/app-release.apk')
signed = subprocess.check_output([str(sdk/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', signed).group(1).lower()
signing = properties('keystore.properties')
env = dict(os.environ, DENTAL_VERIFY_PASSWORD=signing['storePassword'])
export = subprocess.check_output(['keytool', '-exportcert', '-keystore', signing['storeFile'],
    '-alias', signing['keyAlias'], '-storepass:env', 'DENTAL_VERIFY_PASSWORD'], env=env, stderr=subprocess.PIPE)
if certificate != hashlib.sha256(export).hexdigest():
    raise SystemExit('APK signer does not match the configured release key.')
subprocess.run([str(sdk/'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
badging = subprocess.check_output([str(sdk/'aapt'), 'dump', 'badging', str(apk)], text=True)
expected = f"name='com.dentalpro.clinic' versionCode='{code}' versionName='{name}'"
if expected not in badging.splitlines()[0] or 'application-debuggable' in badging:
    raise SystemExit('APK package/version/build type is incorrect.')
libraries = []
with zipfile.ZipFile(apk) as archive:
    for entry in archive.namelist():
        if not entry.startswith('lib/arm64-v8a/') or not entry.endswith('.so'):
            continue
        data = archive.read(entry)
        if data[:6] != b'\x7fELF\x02\x01':
            raise SystemExit('Invalid ARM64 library: ' + entry)
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        alignments = [struct.unpack_from('<Q', data, offset+i*size+48)[0] for i in range(count)
                      if struct.unpack_from('<I', data, offset+i*size)[0] == 1]
        if not alignments or min(alignments) < 16384:
            raise SystemExit('Library lacks 16 KB page alignment: ' + entry)
        libraries.append(entry)
if not libraries:
    raise SystemExit('ARM64 native libraries are missing.')
reports = list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
for report in reports:
    suite = ET.parse(report).getroot()
    for key in counts:
        counts[key] += int(suite.attrib.get(key, 0))
if not reports or not counts['tests'] or counts['failures'] or counts['errors']:
    raise SystemExit('Passing unit test results are required.')
lint = ET.parse('app/build/reports/lint-results-debug.xml').getroot()
if any(issue.attrib.get('severity') in ('Error', 'Fatal') for issue in lint.findall('issue')):
    raise SystemExit('Android lint errors block publication.')
output = Path('dist')
output.mkdir(exist_ok=True)
target = output / f'DentalPro-{name}-release.apk'
shutil.copy2(apk, target)
sha = hashlib.sha256(target.read_bytes()).hexdigest()
(output/'SHA256SUMS.txt').write_text(sha + '  ' + target.name + '\n')
(output/'verification.json').write_text(json.dumps({
    'package': 'com.dentalpro.clinic', 'version_name': name, 'version_code': code,
    'apk_sha256': sha, 'apk_size_bytes': target.stat().st_size,
    'signing_certificate_sha256': certificate, 'unit_tests': counts,
    'lint_errors': 0, 'arm64_libraries_16kb': libraries,
    'commit': os.environ.get('GITHUB_SHA'), 'physical_device_tested': False,
}, indent=2) + '\n')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as handle:
        handle.write(f'tag={tag}\nversion={name}\ncode={code}\nnotes={notes.as_posix()}\n')
print(f'Verified {target.name}; SHA-256: {sha}')
