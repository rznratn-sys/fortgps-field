#!/usr/bin/env python3
"""Real cryptographic CI test with a disposable key. Never uploads keys or test APKs."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import tempfile
import zipfile
from handoff import plan,sha
from owner_key import keytool,run,cert
from local_sign import sign_bundle,verify_signature
from verify import bundle
from portable_tools import verify_jar


def rejected(action):
    try:action()
    except (ValueError,RuntimeError):return
    raise AssertionError('Unsafe operation was accepted')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True)
    p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--report',type=Path,required=True)
    a=p.parse_args();verify_jar(a.jar);os.umask(0o077)
    approved=plan();meta=json.loads((a.input/'handoff.json').read_text());checks=[]
    with tempfile.TemporaryDirectory(prefix='fortgps-disposable-test-') as d:
        root=Path(d);key=root/'TEST-ONLY.p12';password=secrets.token_urlsafe(32)
        run([keytool(),'-genkeypair','-keystore',str(key),'-storetype','PKCS12','-alias','field-test',
             '-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=FortGPS TEST ONLY - NOT FOR DISTRIBUTION',
             '-storepass:env','FIELD_OWNER_PASSWORD','-keypass:env','FIELD_OWNER_PASSWORD'],password=password)
        expected=cert(key,'field-test',password)
        receipt=root/'test-receipt.json'
        receipt.write_text(json.dumps({'test_only':True,'owner_attested_independent_backup':False,
            'certificate_sha256':expected,'keystore_sha256':sha(key),'alias':'field-test',
            'verified_at':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}))
        def sign(output,password_value=password):
            return sign_bundle(a.input,output,key,receipt,a.jar,approved,meta['pipeline_commit'],meta['run_id'],
                               meta['unsigned_sha256'],password_value,test_only=True)
        first=sign(root/'first');second=sign(root/'second')
        if first['signer_certificate_sha256']!=second['signer_certificate_sha256']:raise AssertionError('Identity changed')
        checks+=['real_v1_v2_v3_sign_and_verify','repeat_signing_same_private_identity','unsigned_payload_preserved']
        rejected(lambda:sign(root/'first'));checks+=['existing_output_refused']
        rejected(lambda:sign(root/'bad-password','incorrect-test-password'));checks+=['wrong_password_refused']
        if (root/'bad-password').exists():raise AssertionError('Failed operation exposed a bundle')
        rejected(lambda:verify_signature(root/'first'/approved['apk_name'],a.jar,'0'*64));checks+=['wrong_certificate_refused']
        rejected(lambda:bundle(root/'first',approved,expected,None,meta['pipeline_commit'],jar=a.jar));checks+=['test_key_publication_refused']
        tampered=root/'tampered.apk';shutil.copyfile(root/'first'/approved['apk_name'],tampered)
        with zipfile.ZipFile(tampered,'a') as z:z.writestr('UNAPPROVED.txt',b'tampered fixture')
        rejected(lambda:verify_signature(tampered,a.jar,expected));checks+=['actual_tampered_signature_refused']
        restored=root/'restored.p12';shutil.copyfile(key,restored)
        if cert(restored,'field-test',password)!=expected:raise AssertionError('Restored key differs')
        checks+=['restored_disposable_private_key_usable']
    # Only public test summary survives; TemporaryDirectory removed keys, passwords are not logged.
    report={'architecture':platform.machine(),'system':platform.system(),'checks_passed':checks,
            'private_test_files_removed':not root.exists(),'production_identity_created':False,
            'physical_raspberry_pi_tested':False,'published':False}
    a.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
