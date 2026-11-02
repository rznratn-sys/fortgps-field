#!/usr/bin/env python3
"""Read every public release asset without credentials and with HTTPS verification."""
import argparse
import json
from pathlib import Path
import urllib.request

p = argparse.ArgumentParser()
p.add_argument('--plan', type=Path, required=True)
p.add_argument('--directory', type=Path, required=True)
a = p.parse_args()
plan = json.loads(a.plan.read_text())
base = 'https://api.github.com/repos/' + plan['repository']
with urllib.request.urlopen(base+'/releases/tags/'+plan['tag'], timeout=60) as response:
    release = json.load(response)
if release['draft'] or not release['prerelease'] or release['tag_name'] != plan['tag']:
    raise ValueError('Expected public prerelease')
names = {plan['apk_name'], 'release-manifest.json', 'SHA256SUMS', 'INSTALL.md', 'RELEASE_NOTES.md', 'TEST_REPORT.md'}
if len(release['assets']) != len(names) or {x['name'] for x in release['assets']} != names:
    raise ValueError('Unexpected public assets')
a.directory.mkdir()
for asset in release['assets']:
    url = asset['browser_download_url']
    if not url.startswith('https://github.com/'+plan['repository']+'/releases/download/'+plan['tag']+'/'):
        raise ValueError('Unexpected asset URL')
    with urllib.request.urlopen(url, timeout=60) as response, (a.directory/asset['name']).open('xb') as output:
        while chunk := response.read(1024*1024):
            output.write(chunk)
