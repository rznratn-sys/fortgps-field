#!/usr/bin/env python3
"""Rehearse USB + independent-store recovery using ONLY a two-day disposable key."""
import argparse
from datetime import datetime,timezone,timedelta
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
from encrypted_backup import transform
from handoff import sha,timestamp
from owner_key import external,keytool,run,cert
from pi_preflight import device_info,runtime_info,write_record
from portable_tools import verify_jar


def fresh(value):
    timestamp(value)
    if datetime.now(timezone.utc)-datetime.strptime(value,'%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)>timedelta(days=7):
        raise ValueError('Preflight/recovery drill is older than seven days; repeat it')


def check_profile(p):
    d=device_info()
    if (p.get('kind')!='fortgps-pi-preflight' or not d['physical_pi_observed'] or
        p['device']!=d or p['java']!=runtime_info() or p['crypto'].get('rsa_bits')!=4096 or
        not p['crypto'].get('private_test_files_removed') or len(p['crypto'].get('checks_passed',[]))<10):
        raise ValueError('A fresh successful signing preflight on this physical Pi/runtime is required')
    fresh(p['checked_at'])
    from handoff import ROOT
    if p['apksigner_jar_sha256']!=json.loads((ROOT/'scripts/release/tools-lock.json').read_text())['apksigner_jar_sha256']:
        raise ValueError('Signing tool changed; repeat preflight')
    return p


def validate_preflight(path):
    return check_profile(json.loads(Path(path).read_text()))


def physical_roots(path):
    dev=os.stat(path).st_dev
    start=Path('/sys/dev/block')/f'{os.major(dev)}:{os.minor(dev)}'
    def visit(p,seen):
        if not p.exists():return set()
        p=p.resolve()
        if p in seen:return set()
        seen.add(p)
        slaves=list((p/'slaves').iterdir()) if (p/'slaves').exists() else []
        if slaves:return set().union(*(visit(s,seen) for s in slaves))
        if (p/'partition').exists():p=p.parent
        return {str(p)}
    return visit(start,set())


def usb_check(directory,primary):
    directory=Path(directory).resolve()
    roots=physical_roots(directory);primary_roots=physical_roots(primary)
    if (os.stat(directory).st_dev==os.stat(primary).st_dev or not roots or
        roots & primary_roots or not all('/usb' in r.lower() for r in roots)):
        raise ValueError('Choose an already mounted USB filesystem on a different physical device; no auto-mount/format')
    result=subprocess.run(['findmnt','--json','--target',str(directory),'--output','UUID,FSTYPE'],capture_output=True,text=True,check=True)
    filesystem=json.loads(result.stdout)['filesystems'][0]
    if not filesystem.get('uuid'):raise ValueError('USB filesystem UUID required for safe replug verification')
    return {'directory':str(directory),'volume_uuid':filesystem['uuid'],'filesystem':filesystem['fstype']}


def prepare(preflight,usb_dir,state,password,backup_password,*,test_only=False):
    state=Path(state)
    if state.exists():raise ValueError('State already exists')
    if password==backup_password:raise ValueError('Use different PKCS12 and outer backup passphrases')
    profile=json.loads(Path(preflight).read_text()) if test_only else validate_preflight(preflight)
    usb={'directory':str(Path(usb_dir).resolve()),'volume_uuid':'LAB-ONLY','filesystem':'test'} if test_only else usb_check(usb_dir,state.parent)
    output=Path(usb_dir)/('FortGPS-TEST-RECOVERY-'+uuid.uuid4().hex+'.p12.gpg')
    with tempfile.TemporaryDirectory(prefix='fortgps-recovery-test-',dir=state.parent) as temp:
        root=Path(temp);key=root/'TEST-ONLY.p12'
        run([keytool(),'-genkeypair','-keystore',str(key),'-storetype','PKCS12','-alias','field-test',
             '-keyalg','RSA','-keysize','4096','-validity','2','-dname','CN=FortGPS TEST ONLY - RECOVERY DRILL',
             '-storepass:env','FIELD_OWNER_PASSWORD','-keypass:env','FIELD_OWNER_PASSWORD'],password=password)
        checksum=sha(key);fingerprint=cert(key,'field-test',password)
        transform(key,output,backup_password)
        restored=root/'restored-usb.p12';transform(output,restored,backup_password,decrypt=True)
        if sha(restored)!=checksum or cert(restored,'field-test',password)!=fingerprint:
            raise ValueError('USB private-key recovery failed')
    data={'schema':1,'kind':'fortgps-recovery-drill','test_only':test_only,'preflight':profile,
          'usb':usb,'encrypted_file':str(output.resolve()),'encrypted_sha256':sha(output),
          'test_keystore_sha256':checksum,'test_certificate_sha256':fingerprint,
          'test_private_files_removed':not root.exists(),
          'prepared_at':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}
    write_record(state,data)
    return output


def finish(state,second_copy,readiness,password,backup_password,*,independent_attested=False,test_only=False):
    data=json.loads(Path(state).read_text());second=Path(second_copy)
    if not independent_attested:raise ValueError('Independent storage retrieval must be attested by owner')
    if not test_only:
        if data.get('test_only'):raise ValueError('Cloud/lab rehearsal cannot authorize production creation')
        check_profile(data['preflight'])
        if usb_check(Path(data['encrypted_file']).parent,Path(state).parent)!=data['usb']:
            raise ValueError('USB device changed')
        if os.path.samefile(data['encrypted_file'],second) or os.stat(second).st_dev==os.stat(data['encrypted_file']).st_dev or physical_roots(second)&physical_roots(data['encrypted_file']):
            raise ValueError('Second copy must come from independent storage, not the same USB disk/partition')
    fresh(data['prepared_at'])
    if not data['test_private_files_removed'] or sha(data['encrypted_file'])!=data['encrypted_sha256'] or sha(second)!=data['encrypted_sha256']:
        raise ValueError('Encrypted copies changed or test cleanup missing')
    with tempfile.TemporaryDirectory(prefix='fortgps-second-restore-',dir=Path(state).parent) as temp:
        restored=Path(temp)/'restored-second.p12';transform(second,restored,backup_password,decrypt=True)
        if sha(restored)!=data['test_keystore_sha256'] or cert(restored,'field-test',password)!=data['test_certificate_sha256']:
            raise ValueError('Second-store private-key recovery failed')
    record={'schema':1,'kind':'fortgps-identity-readiness','production_ready':not test_only and not data['test_only'],
            'preflight':data['preflight'],'usb':data['usb'],'encrypted_file':data['encrypted_file'],
            'second_retrieved_file':str(second.resolve()),'encrypted_sha256':data['encrypted_sha256'],
            'independent_storage_attested':True,'verified_restore_copies':2,'test_private_files_removed':not Path(temp).exists(),
            'verified_at':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}
    write_record(readiness,record)
    return record


def validate_readiness(path):
    r=json.loads(external(Path(path)).read_text())
    if (r.get('kind')!='fortgps-identity-readiness' or r.get('production_ready') is not True or
        r.get('verified_restore_copies')!=2 or not r.get('independent_storage_attested') or not r.get('test_private_files_removed')):
        raise ValueError('A completed two-store recovery rehearsal is required BEFORE creating a production key')
    check_profile(r['preflight'])
    fresh(r['verified_at'])
    if usb_check(Path(r['encrypted_file']).parent,Path(path).parent)!=r['usb'] or sha(r['encrypted_file'])!=r['encrypted_sha256'] or sha(r['second_retrieved_file'])!=r['encrypted_sha256']:
        raise ValueError('Verified recovery media/test copies are unavailable or changed')
    return r


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='operation',required=True)
    a=sub.add_parser('prepare');a.add_argument('--preflight',type=Path,required=True);a.add_argument('--usb-dir',type=Path,required=True);a.add_argument('--state',type=Path,required=True)
    a=sub.add_parser('finish');a.add_argument('--state',type=Path,required=True);a.add_argument('--second-copy',type=Path,required=True);a.add_argument('--readiness',type=Path,required=True)
    a=p.parse_args();os.umask(0o077)
    if not sys.stdin.isatty() or os.environ.get('GITHUB_ACTIONS') or os.environ.get('CODEX_CLOUD') or not device_info()['physical_pi_observed']:
        raise ValueError('Run this readiness rehearsal interactively on the owner-controlled physical Pi')
    if a.operation=='prepare' and input('Confirm this mounted USB is your backup device, not storage used by Věc. Type USB BACKUP DEVICE CONFIRMED: ')!='USB BACKUP DEVICE CONFIRMED':
        raise ValueError('USB backup device not approved')
    password=getpass.getpass('DISPOSABLE rehearsal PKCS12 password (save temporarily for finish): ')
    backup=getpass.getpass('Different DISPOSABLE outer backup passphrase (save temporarily for finish): ')
    if len(password)<16 or len(backup)<20:raise ValueError('Use 16+/20+ character rehearsal passwords')
    if a.operation=='prepare':
        if getpass.getpass('Repeat rehearsal PKCS12 password: ')!=password or getpass.getpass('Repeat rehearsal backup passphrase: ')!=backup:raise ValueError('Passphrases differ')
        file=prepare(external(a.preflight),a.usb_dir,external(a.state),password,backup)
        print('USB TEST archive:',file)
        print('Copy ONLY this encrypted TEST archive to independent storage, retrieve it to Pi, then run finish.')
    else:
        if input('Confirm the second file was retrieved from storage physically independent of Pi/USB, with recoverable separate passwords. Type INDEPENDENT COPY RETRIEVED: ')!='INDEPENDENT COPY RETRIEVED':raise ValueError('Retrieval not attested')
        finish(external(a.state),a.second_copy,external(a.readiness),password,backup,independent_attested=True)
        print('Readiness recorded. NO production key was created. Creation still requires separate explicit approval.')

if __name__=='__main__':
    try:main()
    except (ValueError,RuntimeError,OSError,KeyError) as e:sys.exit(str(e))
