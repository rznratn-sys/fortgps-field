#!/usr/bin/env python3
"""Run ONLY on the owner's computer. Never invoke this in disposable CI/cloud.

Passwords are read from a terminal and passed through child process environment,
not command arguments or logs. No secret is written to the repository.
"""
import argparse
from datetime import datetime, timezone
import getpass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

def keytool():
    home = os.environ.get('FORTGPS_JAVA_HOME')
    return str(Path(home)/'bin/keytool') if home else 'keytool'


def run(args, *, password=None, data=None):
    env = os.environ.copy()
    for name in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
        env.pop(name, None)
    if password is not None:
        env['FIELD_OWNER_PASSWORD'] = password
    if Path(args[0]).name == 'keytool':
        args=[args[0], '-J-Xmx256m', *args[1:]]
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    if result.returncode:
        raise RuntimeError('Command failed; private diagnostics suppressed: ' + args[0])
    return result.stdout


def external(path):
    path = path.expanduser().resolve()
    repo = Path(__file__).resolve().parents[2]
    if path == repo or repo in path.parents:
        raise ValueError('Private material and recovery records must be outside this repository')
    return path


def cert(path, alias, password):
    common = ['-keystore', str(path), '-alias', alias, '-storepass:env', 'FIELD_OWNER_PASSWORD']
    # A CSR proves this is a usable private-key entry, not just a certificate.
    run([keytool(), '-certreq', *common, '-keypass:env', 'FIELD_OWNER_PASSWORD'], password=password)
    der = run([keytool(), '-exportcert', *common], password=password)
    return hashlib.sha256(der).hexdigest()


def backup_record(key, restored, alias, password, record, second_restored=None):
    if not key.is_file() or not restored.is_file() or os.path.samefile(key, restored):
        raise ValueError('Provide a separately restored backup file')
    before = hashlib.sha256(key.read_bytes()).hexdigest()
    if hashlib.sha256(restored.read_bytes()).hexdigest() != before:
        raise ValueError('Restored keystore differs')
    if second_restored is None or not second_restored.is_file() or os.path.samefile(key,second_restored) or os.path.samefile(restored,second_restored):
        raise ValueError('Two separately restored private-key copies are required')
    if hashlib.sha256(second_restored.read_bytes()).hexdigest()!=before:
        raise ValueError('Second restored keystore differs')
    fingerprint = cert(restored, alias, password)
    if cert(second_restored,alias,password)!=fingerprint:
        raise ValueError('Second restored private key differs')
    if cert(key, alias, password) != fingerprint:
        raise ValueError('Certificate differs')
    if input('Independent encrypted backup exists on separate media; password is recoverable separately. Type BACKUP VERIFIED: ') != 'BACKUP VERIFIED':
        raise ValueError('No owner recovery attestation')
    data = {'certificate_sha256': fingerprint, 'keystore_sha256': before, 'alias': alias,
            'verified_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'owner_attested_independent_backup': True, 'verified_backup_copies': 2}
    with record.open('x') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
    print('Recovery verified. Public certificate SHA-256:', fingerprint)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['create', 'verify-backup'])
    p.add_argument('--keystore', required=True, type=Path)
    p.add_argument('--alias', default='field-release')
    p.add_argument('--restored', type=Path)
    p.add_argument('--receipt', type=Path)
    p.add_argument('--second-restored', type=Path)
    p.add_argument('--readiness', type=Path, help='Successful physical Pi signing + two-store rehearsal; required for create')
    a = p.parse_args()
    if not sys.stdin.isatty() or os.environ.get('GITHUB_ACTIONS') or os.environ.get('CODEX_CLOUD'):
        raise ValueError('Use an interactive terminal on the owner-controlled device')
    os.umask(0o077)
    key = external(a.keystore)
    if a.command == 'create':
        import platform
        if platform.system() != 'Linux' or platform.machine() not in ['aarch64', 'arm64']:
            raise ValueError('Production identity creation requires owner-controlled Linux ARM64')
        if not a.readiness:
            raise ValueError('--readiness required: rehearse BOTH encrypted backups before production creation')
        from recovery_readiness import validate_readiness
        validate_readiness(a.readiness)
        if key.parent!=external(a.readiness).parent:
            raise ValueError('Production keystore must stay in the verified private Pi directory, not USB/repository')
        if key.exists():
            raise ValueError('Refusing to replace an existing keystore')
        if input('On your own device, authorize a new long-term signing identity. Type CREATE: ') != 'CREATE':
            raise ValueError('Creation not approved')
        password = getpass.getpass('New keystore password (store separately in a password manager): ')
        if len(password) < 16 or getpass.getpass('Repeat password: ') != password:
            raise ValueError('Passwords differ or are shorter than 16 characters')
        key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        run([keytool(), '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-alias', a.alias,
             '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA', '-validity', '10000',
             '-dname', 'CN=FortGPS Field Release', '-storepass:env', 'FIELD_OWNER_PASSWORD',
             '-keypass:env', 'FIELD_OWNER_PASSWORD'], password=password)
        print('Created. Public certificate SHA-256:', cert(key, a.alias, password))
        print('Not uploaded. Create independent encrypted backups and perform a restore drill next.')
    else:
        password = getpass.getpass('Keystore password: ')
        if not a.restored or not a.receipt:
            raise ValueError('--restored and --receipt required')
        backup_record(key, external(a.restored), a.alias, password, external(a.receipt), external(a.second_restored) if a.second_restored else None)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        sys.exit(str(error))
