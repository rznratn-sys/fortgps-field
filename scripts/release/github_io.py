#!/usr/bin/env python3
"""GitHub transport for PUBLIC bundles only. No signing/key code."""
import argparse
import json
import os
from pathlib import Path
import subprocess
from handoff import check_input, commit, digest, plan


def api(endpoint):
    result=subprocess.run(['gh','api',endpoint],capture_output=True,text=True)
    if result.returncode:raise RuntimeError('GitHub read failed; refusing to continue')
    return json.loads(result.stdout)


def build_run(run_id, pipeline):
    if not str(run_id).isdigit():raise ValueError('Invalid run ID')
    p=plan();r=api(f"repos/{p['repository']}/actions/runs/{run_id}")
    if (r['repository']['full_name']!=p['repository'] or r['head_sha']!=commit(pipeline) or
        r['head_branch']!='main' or r['event']!='workflow_dispatch' or r['conclusion']!='success' or
        r['path']!='.github/workflows/publish-alpha3.yml'):
        raise ValueError('Unsigned build must be a successful approved manual main run')
    return r


def fetch_unsigned(run_id,pipeline,expected_sha,output):
    p=plan();r=build_run(run_id,pipeline)
    if output.exists():raise ValueError('Refusing to replace download directory')
    output.mkdir(mode=0o700)
    name=f"field-unsigned-{run_id}-{r['run_attempt']}"
    subprocess.run(['gh','run','download',str(run_id),'--repo',p['repository'],'--name',name,'--dir',str(output)],check=True)
    return check_input(output,p,pipeline,run_id,expected_sha)


def absent(p):
    result=subprocess.run(['gh','api','--paginate','--slurp',f"repos/{p['repository']}/releases?per_page=100"],capture_output=True,text=True,check=True)
    if any(r['tag_name']==p['tag'] for page in json.loads(result.stdout) for r in page):
        raise ValueError('Existing release/draft; refusing replacement')
    ref=subprocess.run(['gh','api',f"repos/{p['repository']}/git/ref/tags/{p['tag']}"],capture_output=True,text=True)
    if ref.returncode==0 or json.loads(ref.stdout).get('status')!='404':
        raise ValueError('Existing tag or cannot establish absence')


def draft(release_id):
    if not str(release_id).isdigit():raise ValueError('Invalid draft release ID')
    p=plan();r=api(f"repos/{p['repository']}/releases/{release_id}")
    expected={p['apk_name'],'release-manifest.json','SHA256SUMS','INSTALL.md','RELEASE_NOTES.md','TEST_REPORT.md'}
    if (r['tag_name']!=p['tag'] or r.get('target_commitish')!=p['source_commit'] or r['draft'] is not True or r['prerelease'] is not True or
        len(r['assets'])!=6 or {a['name'] for a in r['assets']}!=expected):
        raise ValueError('Expected complete private draft prerelease')
    return r


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run',required=True)
    p.add_argument('--pipeline-sha',required=True)
    p.add_argument('--unsigned-sha256',required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    fetch_unsigned(a.run,a.pipeline_sha,digest(a.unsigned_sha256),a.output)
    print('Verified unsigned handoff downloaded. No private key was accessed.')

if __name__=='__main__':main()
