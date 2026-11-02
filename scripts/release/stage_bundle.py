#!/usr/bin/env python3
"""Validate locally by default. Explicit future approval can upload six PUBLIC files to a draft."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from handoff import check_input, payload_equal, plan
from portable_tools import verify_jar
from verify import bundle, file_names
from github_io import absent


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['directory','input','jar']:p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--pipeline-sha',required=True)
    p.add_argument('--build-run',required=True)
    p.add_argument('--unsigned-sha256',required=True)
    p.add_argument('--cert-sha256',required=True)
    p.add_argument('--create-draft',action='store_true')
    a=p.parse_args();approved=plan();verify_jar(a.jar)
    meta=check_input(a.input,approved,a.pipeline_sha,a.build_run,a.unsigned_sha256)
    bundle(a.directory,approved,a.cert_sha256,None,a.pipeline_sha,jar=a.jar)
    m=json.loads((a.directory/'release-manifest.json').read_text())
    if m['run_id']!=meta['run_id'] or m['unsigned_sha256']!=meta['unsigned_sha256'] or m['build']!=meta['build']:
        raise ValueError('Delivery is not tied to this unsigned build')
    payload_equal(a.directory/approved['apk_name'],meta['entries'])
    if not a.create_draft:
        print('Public bundle validated. Nothing uploaded.');return
    if not sys.stdin.isatty() or input('Upload PUBLIC files only. Type STAGE '+approved['tag']+': ')!='STAGE '+approved['tag']:
        raise ValueError('Draft upload not approved')
    absent(approved)
    assets=[str(a.directory/n) for n in file_names(approved)+['SHA256SUMS']]
    subprocess.run(['gh','release','create',approved['tag'],'--repo',approved['repository'],
                    '--target',approved['source_commit'],'--draft','--prerelease','--title','FortGPS Field '+approved['version_name'],
                    '--notes-file',str(a.directory/'RELEASE_NOTES.md'),*assets],check=True)
    print('Draft uploaded. Not published. A separately approved GitHub validation/publish run is required.')

if __name__=='__main__':main()
