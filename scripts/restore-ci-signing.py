#!/usr/bin/env python3
"""Restore trusted repository signing secrets without printing their contents."""
import base64
import os
from pathlib import Path

destination = Path(os.environ['RUNNER_TEMP']) / 'dentalpro-signing'
destination.mkdir(mode=0o700, exist_ok=True)
key = destination / 'release.jks'
key.write_bytes(base64.b64decode(os.environ['SIGNING_KEY'], validate=True))
key.chmod(0o600)
properties = os.environ['SIGNING_PROPERTIES'].splitlines()
properties = [line for line in properties if not line.strip().startswith('storeFile=')]
required = {'storePassword', 'keyPassword', 'keyAlias'}
present = {line.split('=', 1)[0].strip() for line in properties if '=' in line}
if not required <= present:
    raise SystemExit('Signing properties are incomplete.')
config = Path('keystore.properties')
config.write_text('\n'.join(properties) + '\nstoreFile=' + str(key) + '\n')
config.chmod(0o600)

