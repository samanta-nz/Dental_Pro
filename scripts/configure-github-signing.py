#!/usr/bin/env python3
"""Set repository Actions secrets from a private signing backup using authenticated gh."""
import argparse
import base64
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('keystore', type=Path)
parser.add_argument('properties', type=Path)
parser.add_argument('--repo', default='samanta-nz/Dental_Pro')
args = parser.parse_args()
subprocess.run(['gh', 'auth', 'status'], check=True)
for name, value in (
    ('ANDROID_KEYSTORE_BASE64', base64.b64encode(args.keystore.read_bytes())),
    ('ANDROID_KEYSTORE_PROPERTIES', args.properties.read_bytes()),
):
    subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo], input=value, check=True)
print('Signing secrets configured. Keep the private backup for future releases.')

