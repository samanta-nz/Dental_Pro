#!/usr/bin/env python3
"""Upload a signed APK to Supabase and atomically announce it after the upload succeeds.

Requires SUPABASE_SERVICE_ROLE_KEY in the publisher's environment; never embed it in the APK.
The platform's configured proxy and CA trust remain active. Usage:
 python scripts/publish-release.py path/to/app-release.apk --code 70001 --name 7.0.1 --notes notes.txt
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import ssl
import urllib.error
import urllib.parse
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
p.add_argument('--code', type=int, required=True)
p.add_argument('--name', required=True)
p.add_argument('--notes', type=Path, required=True)
p.add_argument('--url', default='https://geifrkzmiutpkbwejdms.supabase.co')
p.add_argument('--mandatory', action='store_true')
a = p.parse_args()
secret = os.environ.get('SUPABASE_SERVICE_ROLE_KEY')
if not secret:
    p.error('Set SUPABASE_SERVICE_ROLE_KEY only in the trusted publishing environment.')
if a.code <= 0 or not a.apk.is_file() or not a.notes.is_file() or urllib.parse.urlparse(a.url).scheme != 'https':
    p.error('A signed APK, notes, positive version code, and HTTPS project URL are required.')
payload = a.apk.read_bytes()
sha = hashlib.sha256(payload).hexdigest()
object_name = f'dentalpro-{a.name}-{sha[:12]}.apk'
headers = {'apikey': secret, 'Authorization': 'Bearer ' + secret}
trust = os.environ.get('SSL_CERT_FILE') or os.environ.get('REQUESTS_CA_BUNDLE')
context = ssl.create_default_context(cafile=trust)
def send(path, data, content_type, extra=None):
    request = urllib.request.Request(a.url.rstrip('/') + path, data=data,
        headers=dict(headers, **{'Content-Type': content_type}, **(extra or {})), method='POST')
    try:
        with urllib.request.urlopen(request, context=context, timeout=300) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        # Keep credential-bearing requests and upstream response details out of logs.
        raise SystemExit(f'Publication failed at {path.split("?")[0]}: HTTP {e.code}') from None
send('/storage/v1/object/app_releases/' + urllib.parse.quote(object_name), payload,
     'application/vnd.android.package-archive', {'x-upsert': 'true'})
public_url = a.url.rstrip('/') + '/storage/v1/object/public/app_releases/' + urllib.parse.quote(object_name)
release = {'version_code': a.code, 'version_name': a.name, 'apk_url': public_url,
           'apk_sha256': sha, 'apk_size_bytes': len(payload), 'changelog': a.notes.read_text(),
           'is_active': True, 'is_mandatory': a.mandatory}
send('/rest/v1/app_updates?on_conflict=version_code', json.dumps(release).encode(),
     'application/json', {'Prefer': 'resolution=merge-duplicates,return=minimal'})
print(json.dumps({k: release[k] for k in ('version_code', 'version_name', 'apk_url', 'apk_sha256', 'apk_size_bytes')}, indent=2))
