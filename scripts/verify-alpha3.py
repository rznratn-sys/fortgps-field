#!/usr/bin/env python3
"""Validate release identity and emit a manifest. Never handles private signing material."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

CERT256 = 'ef2ee10dc4833159a50f0b550e8eb4eeac53f21ef47079136b5151990b74fb72'
CERT1 = 'b7c8490077670bec9594bf4cf66ab209a1431d8d'

def verify_certificate(text):
    if re.findall(r'^Number of signers:\s*(\d+)\s*$', text, re.M) != ['1']:
        raise ValueError('Exactly one signer required')
    for name, expected in [('SHA-256',CERT256),('SHA-1',CERT1)]:
        values=[]
        for line in text.splitlines():
            field=f'certificate {name} digest:'
            if field in line:
                value=line.split(field,1)[1].strip().lower()
                if not re.fullmatch('[0-9a-f]{'+str(len(expected))+'}',value):
                    raise ValueError('Malformed certificate digest')
                values.append(value)
        if not values or set(values)!={expected}:
            raise ValueError('Unexpected/missing signing certificate')

def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def run(*args): return subprocess.check_output(args,text=True)

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--apk',type=Path,required=True)
    p.add_argument('--apksigner',required=True)
    p.add_argument('--aapt',required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    # A dirty source worktree cannot substantiate commit provenance.
    subprocess.run(['git','-C',str(root),'diff','--quiet','HEAD'],check=True)
    if run('git','-C',str(root),'ls-files','--others','--exclude-standard').strip():
        raise ValueError('Untracked files in source worktree')
    cert=run(a.apksigner,'verify','--verbose','--print-certs',str(a.apk))
    verify_certificate(cert)
    package=run(a.aapt,'dump','badging',str(a.apk)).splitlines()[0]
    if not re.search(r"name='cz.fortgps.field' versionCode='20002' versionName='2.0.0-alpha3'",package):
        raise ValueError('Unexpected package/version')
    reports=list((root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports: raise ValueError('Missing unit test reports')
    counts={k:0 for k in ['tests','failures','errors','skipped']}
    for report in reports:
        node=ET.parse(report).getroot()
        for k in counts: counts[k]+=int(node.get(k,'0'))
    if counts['failures'] or counts['errors']: raise ValueError('Tests failed')
    lint={}
    for variant in ['debug','release']:
        report=ET.parse(root/f'android/app/build/reports/lint-results-{variant}.xml').getroot()
        issues=report.findall('issue')
        lint[variant]={'errors':sum(i.get('severity') in ['Error','Fatal'] for i in issues),
                       'warnings':sum(i.get('severity')=='Warning' for i in issues)}
        if lint[variant]['errors']: raise ValueError('Android lint failed')
    py=subprocess.run([sys.executable,'-m','unittest','discover','-s','tests','-v'],cwd=root,
                      text=True,capture_output=True,check=True)
    py_count=int(re.search(r'Ran (\d+) tests',py.stderr).group(1))
    with zipfile.ZipFile(a.apk) as z:
        embedded={n:z.read(n).decode('utf-8') for n in z.namelist() if n=='META-INF/version-control-info.textproto'}
    obj={
        'version':'2.0.0-alpha3','package':'cz.fortgps.field','version_code':20002,
        'build_type':'release','source_commit':run('git','-C',str(root),'rev-parse','HEAD').strip(),
        'source_tree':run('git','-C',str(root),'rev-parse','HEAD^{tree}').strip(),
        'source_provenance':'Clean tracked/untracked worktree checked before manifest; build and tests from this source tree',
        'apk':{'name':a.apk.name,'size_bytes':a.apk.stat().st_size,'sha256':digest(a.apk)},
        'signer_certificate_sha256':CERT256,'signer_certificate_sha1':CERT1,
        'unit_test_report_counts':counts,'python_contract_and_identity_tests_passed':py_count,
        'android_lint':lint,'embedded_vcs_info':embedded,
        'provenance_note':'AGP reports NO_SUPPORTED_VCS_FOUND for nested Android project; external manifest pins the clean Git commit/tree.',
        'physical_samsung_verified':False,'samsung_gallery_verified':False,
        'camera_scene_info_and_gallery_dc_data':'safely_refused_not_supported',
        'room_version':2,'secrets_included':False,
    }
    a.output.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(obj,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
