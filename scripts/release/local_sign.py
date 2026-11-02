#!/usr/bin/env python3
"""Offline local signing. No HTTP/GitHub client; never exports a private key."""
import argparse
import getpass
import json
import os
from pathlib import Path
import platform
import sys
import tempfile
from handoff import ROOT, check_input, digest, exact_files, payload_equal, plan, sha, timestamp
from owner_key import cert, external, run
from portable_tools import java_command, verify_jar
import verify as public_verify


def verify_signature(apk, jar, expected, allow_test=False):
    text=run(java_command(jar,'verify','--verbose','--print-certs',str(apk))).decode()
    public_verify.certificate(text,expected)
    public_verify.reject_test_certificate(text,allow_test)
    import re
    values=re.findall(r'^Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]{40})$',text,re.M)
    if len(values)!=1:
        raise ValueError('Missing certificate SHA-1')
    return values[0].lower()


def receipt_check(receipt, key, password, test_only):
    data=json.loads(Path(receipt).read_text())
    if test_only:
        if data.get('test_only') is not True:
            raise ValueError('Test mode requires explicitly disposable test-key receipt')
    elif data.get('test_only') or data.get('owner_attested_independent_backup') is not True:
        raise ValueError('Production signing requires owner-verified independent backup')
    if data['keystore_sha256']!=sha(key) or cert(key,data['alias'],password)!=digest(data['certificate_sha256']):
        raise ValueError('Keystore/receipt/certificate mismatch')
    timestamp(data['verified_at'])
    return data


def sign_bundle(input_dir, output, key, receipt, jar, p, pipeline, run_id, unsigned_sha, password, test_only=False):
    # Every public input and tool is validated BEFORE a private-key operation.
    jar_sha=verify_jar(jar)
    meta=check_input(input_dir,p,pipeline,run_id,unsigned_sha)
    if output.exists():
        raise ValueError('Refusing to replace an output directory')
    key=external(Path(key));receipt=external(Path(receipt))
    if key.stat().st_mode & 0o077:
        raise ValueError('Keystore must be private to its owner (mode 600)')
    data=receipt_check(receipt,key,password,test_only)
    # Atomic publication of a local directory: failed signing exposes no delivery bundle.
    with tempfile.TemporaryDirectory(prefix='.field-sign-',dir=output.parent) as temp:
        staging=Path(temp)
        apk=staging/p['apk_name']
        run(java_command(jar,'sign','--ks',str(key),'--ks-key-alias',data['alias'],
             '--ks-pass','env:FIELD_OWNER_PASSWORD','--key-pass','env:FIELD_OWNER_PASSWORD',
             '--v1-signer-name','FIELD','--v1-signing-enabled','true','--v2-signing-enabled','true',
             '--v3-signing-enabled','true','--v4-signing-enabled','false','--out',str(apk),
             str(Path(input_dir)/'unsigned.apk')),password=password)
        sha1=verify_signature(apk,jar,data['certificate_sha256'],allow_test=test_only)
        payload_equal(apk,meta['entries'])
        manifest=dict(p, schema=2, signing_mode='owner-local', test_only=bool(test_only),
                      pipeline_commit=meta['pipeline_commit'],run_id=meta['run_id'],run_attempt=meta['run_attempt'],
                      unsigned_sha256=unsigned_sha, apk_sha256=sha(apk), apk_size=apk.stat().st_size,
                      signer_certificate_sha256=data['certificate_sha256'],signer_certificate_sha1=sha1,
                      key_backup_verified_at=data['verified_at'],apksigner_jar_sha256=jar_sha,build=meta['build'],
                      physical_samsung_verified=False,samsung_sef_unknown_variants='refused')
        (staging/'release-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        for name in ['INSTALL.md','RELEASE_NOTES.md']:
            (staging/name).write_bytes((Path(input_dir)/name).read_bytes())
        j=meta['build']['junit']
        (staging/'TEST_REPORT.md').write_text(
            f"# FortGPS Field {p['version_name']}\n\nSource `{p['source_commit']}`; unsigned build run `{run_id}`.\n"
            f"Pipeline `{pipeline}`. Locally signed; disposable test identity: {bool(test_only)}.\n\n"
            f"JUnit: {j['tests']-j['skipped']} passed, {j['skipped']} skipped, zero failures/errors.\n"
            f"Python source {meta['build']['python_tests']}; release tools {meta['build']['pipeline_python_tests']}.\n"
            f"Lint: {json.dumps(meta['build']['lint'])}.\n\n"
            "Java apksigner verified certificate/signature; every original ZIP entry matches unsigned build.\n"
            "Physical Raspberry Pi, Samsung, migration, live GNSS/Gallery/Drive require separate validation.\n")
        names=public_verify.file_names(p)
        (staging/'SHA256SUMS').write_text(''.join(f'{sha(staging/n)}  {n}\n' for n in names))
        public_verify.bundle(staging,p,data['certificate_sha256'],None,pipeline,jar=jar,allow_test=test_only)
        os.rename(staging,output)
    return manifest


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['input','output','keystore','receipt','jar']:
        p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--pipeline-sha',required=True)
    p.add_argument('--build-run',required=True)
    p.add_argument('--unsigned-sha256',required=True)
    p.add_argument('--test-only',action='store_true')
    a=p.parse_args()
    os.umask(0o077)
    if a.test_only:
        password=os.environ.get('FIELD_TEST_KEY_PASSWORD')
        if not password:raise ValueError('Disposable test password missing')
    else:
        if (not sys.stdin.isatty() or os.environ.get('GITHUB_ACTIONS') or os.environ.get('CODEX_CLOUD') or
                platform.system()!='Linux' or platform.machine() not in ['aarch64','arm64']):
            raise ValueError('Use an interactive terminal on the owner-controlled ARM64 Pi')
        if input('Authorize local signing (private key stays here). Type SIGN '+plan()['version_name']+': ')!='SIGN '+plan()['version_name']:
            raise ValueError('Signing not approved')
        password=getpass.getpass('Local keystore password: ')
    m=sign_bundle(a.input,a.output,a.keystore,a.receipt,a.jar,plan(),a.pipeline_sha,a.build_run,a.unsigned_sha256,password,a.test_only)
    print('Verified local bundle. APK SHA-256:',m['apk_sha256'])
    print('No network request or publication was performed.')

if __name__=='__main__':
    try:main()
    except (ValueError,RuntimeError,OSError,KeyError) as e:sys.exit(str(e))
