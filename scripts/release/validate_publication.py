#!/usr/bin/env python3
"""Verify draft and original GitHub build independently. Reads no private signing material."""
import json
import os
from pathlib import Path
import subprocess
from github_io import api, build_run, draft
from handoff import check_input, digest, payload_equal, plan, timestamp
from verify import bundle


def main():
    p=plan();env=os.environ
    if env.get('FIELD_PUBLICATION_ENABLED')!='true':
        raise ValueError('Publication explicitly disabled')
    pipeline=env['GITHUB_SHA']
    if env.get('FIELD_RELEASE_PIPELINE_SHA')!=pipeline:
        raise ValueError('Publication pipeline has not been approved')
    cert=digest(env['FIELD_RELEASE_CERT_SHA256'])
    backup=timestamp(env['FIELD_KEY_BACKUP_VERIFIED_AT'])
    r=draft(env['DRAFT_RELEASE_ID'])
    build_run(env['BUILD_RUN_ID'],pipeline)
    subprocess.run(['gh','release','download',p['tag'],'--repo',p['repository'],'--dir','delivery'],check=True)
    m=json.loads(Path('delivery/release-manifest.json').read_text())
    if m['run_id']!=env['BUILD_RUN_ID'] or m['key_backup_verified_at']!=backup:
        raise ValueError('Build or backup attestation mismatch')
    build=build_run(m['run_id'],pipeline)
    subprocess.run(['gh','run','download',m['run_id'],'--repo',p['repository'],
                    '--name',f"field-unsigned-{m['run_id']}-{build['run_attempt']}",'--dir','original'],check=True)
    meta=check_input(Path('original'),p,pipeline,m['run_id'],m['unsigned_sha256'])
    if m['build']!=meta['build'] or m['run_attempt']!=meta['run_attempt']:
        raise ValueError('Build evidence changed')
    bundle(Path('delivery'),p,cert,Path(env['ANDROID_HOME'])/'build-tools/35.0.0',pipeline)
    payload_equal(Path('delivery')/p['apk_name'],meta['entries'])
    subprocess.run([str(Path(env['ANDROID_HOME'])/'build-tools/35.0.0/zipalign'),'-c','-P','16','4','delivery/'+p['apk_name']],check=True)
    for name in ['INSTALL.md','RELEASE_NOTES.md']:
        if (Path('delivery')/name).read_bytes()!=(Path('original')/name).read_bytes():
            raise ValueError('Release document differs from approved build')
    ref=subprocess.run(['gh','api',f"repos/{p['repository']}/git/ref/tags/{p['tag']}"],capture_output=True,text=True)
    if ref.returncode==0:
        obj=json.loads(ref.stdout)['object']
        if obj.get('type')!='commit' or obj.get('sha')!=p['source_commit']:
            raise ValueError('Release tag points to a different source')
    elif json.loads(ref.stdout).get('status')!='404':
        raise ValueError('Cannot establish release tag state')
    # This step validates ONLY. A separate workflow step performs the irreversible transition.
    Path('validated-draft-id').write_text(str(r['id']))
    print('Draft, signature, package, version, payload and original build verified. Still unpublished.')

if __name__=='__main__':main()
