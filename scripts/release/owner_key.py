#!/usr/bin/env python3
"""Run ONLY on the owner's computer. Never invoke this in disposable CI/cloud.

Passwords are read from a terminal and passed through child process environment,
not command arguments or logs. No secret is written to the repository.
"""
import argparse
import base64
from datetime import datetime, timezone
import getpass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = 'rznratn-sys/fortgps-field'
ENVIRONMENT = 'field-release'


def run(args, *, password=None, data=None):
    env = os.environ.copy()
    if password is not None:
        env['FIELD_OWNER_PASSWORD'] = password
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
    run(['keytool', '-certreq', *common, '-keypass:env', 'FIELD_OWNER_PASSWORD'], password=password)
    der = run(['keytool', '-exportcert', *common], password=password)
    return hashlib.sha256(der).hexdigest()


def backup_record(key, restored, alias, password, record):
    if not key.is_file() or not restored.is_file() or os.path.samefile(key, restored):
        raise ValueError('Provide a separately restored backup file')
    before = hashlib.sha256(key.read_bytes()).hexdigest()
    if hashlib.sha256(restored.read_bytes()).hexdigest() != before:
        raise ValueError('Restored keystore differs')
    fingerprint = cert(restored, alias, password)
    if cert(key, alias, password) != fingerprint:
        raise ValueError('Certificate differs')
    if input('Independent encrypted backup exists on separate media; password is recoverable separately. Type BACKUP VERIFIED: ') != 'BACKUP VERIFIED':
        raise ValueError('No owner recovery attestation')
    data = {'certificate_sha256': fingerprint, 'keystore_sha256': before, 'alias': alias,
            'verified_at': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'owner_attested_independent_backup': True}
    with record.open('x') as f:
        json.dump(data, f, indent=2)
        f.write('\n')
    print('Recovery verified. Public certificate SHA-256:', fingerprint)


def check_environment():
    # Read metadata only. Never fetch secret values (GitHub cannot return them).
    env = json.loads(run(['gh', 'api', f'repos/{REPO}/environments/{ENVIRONMENT}']))
    if env.get('can_admins_bypass', True):
        raise ValueError('Disable administrator bypass of environment protection')
    policy = env.get('deployment_branch_policy') or {}
    if not policy.get('custom_branch_policies'):
        raise ValueError('Environment must allow only the main branch')
    branches = json.loads(run(['gh', 'api', f'repos/{REPO}/environments/{ENVIRONMENT}/deployment-branch-policies?per_page=100']))
    rules = branches.get('branch_policies', [])
    if len(rules) != 1 or rules[0].get('name') != 'main' or rules[0].get('type') != 'branch':
        raise ValueError('Environment must allow exactly branch main, no tags')
    approvals = [r for r in env.get('protection_rules', []) if r.get('type') == 'required_reviewers']
    if not approvals or not approvals[0].get('reviewers') or not approvals[0].get('prevent_self_review'):
        raise ValueError('Required independent environment reviewer / prevent self review missing')


def upload(key, alias, password, receipt, pipeline):
    import re
    if not re.fullmatch('[0-9a-f]{40}', pipeline or ''):
        raise ValueError('Approve the final merged pipeline SHA, not a branch name')
    data = json.loads(receipt.read_text())
    fingerprint = cert(key, alias, password)
    if (data.get('certificate_sha256') != fingerprint or data.get('alias') != alias or
            data.get('keystore_sha256') != hashlib.sha256(key.read_bytes()).hexdigest() or
            data.get('owner_attested_independent_backup') is not True):
        raise ValueError('Backup receipt does not match this key')
    check_environment()
    # Never upload secrets into repository-wide scope. Such secrets are exposed to other workflows.
    if input(f'Type UPLOAD {REPO} field-release to authorize uploading this backed-up key: ') != f'UPLOAD {REPO} field-release':
        raise ValueError('Upload not approved')
    values = {'FIELD_RELEASE_KEYSTORE_BASE64': base64.b64encode(key.read_bytes()),
              'FIELD_STORE_PASSWORD': password.encode(), 'FIELD_KEY_PASSWORD': password.encode(),
              'FIELD_KEY_ALIAS': alias.encode()}
    if len(values['FIELD_RELEASE_KEYSTORE_BASE64']) > 48000:
        raise ValueError('Keystore exceeds GitHub secret size budget')
    for name, value in values.items():
        run(['gh', 'secret', 'set', name, '--repo', REPO, '--env', ENVIRONMENT], data=value)
    # The attestation is installed LAST. Partial uploads fail closed in the workflow.
    variables = {'FIELD_RELEASE_CERT_SHA256': fingerprint, 'FIELD_KEY_BACKUP_CERT_SHA256': fingerprint,
                 'FIELD_RELEASE_PIPELINE_SHA': pipeline, 'FIELD_KEY_BACKUP_VERIFIED_AT': data['verified_at']}
    for name, value in variables.items():
        run(['gh', 'variable', 'set', name, '--repo', REPO, '--env', ENVIRONMENT, '--body', value])
    run(['gh', 'variable', 'set', 'FIELD_RELEASE_CERT_SHA256', '--repo', REPO, '--body', fingerprint])
    print('Environment signing configuration uploaded. No workflow was started.')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['create', 'verify-backup', 'upload'])
    p.add_argument('--keystore', required=True, type=Path)
    p.add_argument('--alias', default='field-release')
    p.add_argument('--restored', type=Path)
    p.add_argument('--receipt', type=Path)
    p.add_argument('--pipeline-sha')
    a = p.parse_args()
    if not sys.stdin.isatty() or os.environ.get('GITHUB_ACTIONS') or os.environ.get('CODEX_CLOUD'):
        raise ValueError('Use an interactive terminal on the owner-controlled device')
    os.umask(0o077)
    key = external(a.keystore)
    if a.command == 'create':
        if key.exists():
            raise ValueError('Refusing to replace an existing keystore')
        if input('On your own device, authorize a new long-term signing identity. Type CREATE: ') != 'CREATE':
            raise ValueError('Creation not approved')
        password = getpass.getpass('New keystore password (store separately in a password manager): ')
        if len(password) < 16 or getpass.getpass('Repeat password: ') != password:
            raise ValueError('Passwords differ or are shorter than 16 characters')
        key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-alias', a.alias,
             '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA', '-validity', '10000',
             '-dname', 'CN=FortGPS Field Release', '-storepass:env', 'FIELD_OWNER_PASSWORD',
             '-keypass:env', 'FIELD_OWNER_PASSWORD'], password=password)
        print('Created. Public certificate SHA-256:', cert(key, a.alias, password))
        print('Not uploaded. Create independent encrypted backups and perform a restore drill next.')
    else:
        password = getpass.getpass('Keystore password: ')
        if a.command == 'verify-backup':
            if not a.restored or not a.receipt:
                raise ValueError('--restored and --receipt required')
            backup_record(key, external(a.restored), a.alias, password, external(a.receipt))
        else:
            if not a.receipt:
                raise ValueError('--receipt required')
            upload(key, a.alias, password, external(a.receipt), a.pipeline_sha)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        sys.exit(str(error))
