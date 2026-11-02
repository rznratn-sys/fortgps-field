#!/usr/bin/env python3
"""Public release validation. This program never reads signing secrets."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fingerprint(value):
    if not re.fullmatch(r'[0-9a-f]{64}', value or ''):
        raise ValueError('Expected a lowercase SHA-256 certificate fingerprint')
    return value


def certificate(text, expected):
    fingerprint(expected)
    if re.findall(r'^Number of signers:\s*(\d+)\s*$', text, re.M) != ['1']:
        raise ValueError('Exactly one APK signer required')
    values = re.findall(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$', text, re.M)
    if len(values) != 1 or values[0].lower() != expected:
        raise ValueError('Missing or unexpected certificate')
    if not re.search(r'^Verified using v2 scheme .*: true$', text, re.M):
        raise ValueError('APK v2 signature required')


def identity(text, plan):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    if not package or package.groups() != (plan['package'], str(plan['version_code']), plan['version_name']):
        raise ValueError('Unexpected package or version')
    if re.search(r'^application-(debuggable|testOnly)(?:\s|:|$)', text, re.M):
        raise ValueError('Debug/test APK forbidden')


def manifest_flags(text):
    flags = re.findall(r'^\s*A: android:(?:debuggable|testOnly)\([^)]*\)=.*$', text, re.M)
    if any(not re.search(r'=\(type 0x12\)0x0+$', line) for line in flags):
        raise ValueError('Manifest enables debug/test installation or has an unknown flag encoding')


def reject_test_certificate(text, allow_test=False):
    if not allow_test and re.search(r'^Signer #1 certificate DN:.*FortGPS TEST ONLY',text,re.M|re.I):
        raise ValueError('Disposable test certificate subject forbidden for production')


def verify_apk(apk, plan, cert, tools):
    # check_output also checks the tool exit code: parser text cannot bypass a bad signature.
    signature = subprocess.check_output([str(tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    certificate(signature, cert)
    reject_test_certificate(signature)
    badging = subprocess.check_output([str(tools/'aapt'), 'dump', 'badging', str(apk)], text=True)
    identity(badging, plan)
    xml = subprocess.check_output([str(tools/'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True)
    manifest_flags(xml)
    sha1 = re.findall(r'^Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]{40})$', signature, re.M)
    if len(sha1) != 1:
        raise ValueError('Missing certificate SHA-1 for OAuth identity')
    return sha1[0].lower()


def python_count(report):
    py = report.read_text()
    match = re.search(r'Ran (\d+) tests?', py)
    if not match or not re.search(r'^OK(?:\s|$)', py, re.M) or re.search(r'^(FAILED|ERROR|FAIL):?', py, re.M):
        raise ValueError('Missing or failing Python tests')
    if int(match[1]) <= 0:
        raise ValueError('No Python tests executed')
    return int(match[1])


def evidence(root, python_report, pipeline_report=None):
    reports = sorted((root/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports:
        raise ValueError('Missing JUnit reports')
    counts = dict.fromkeys(['tests', 'failures', 'errors', 'skipped'], 0)
    skipped = []
    for path in reports:
        node = ET.parse(path).getroot()
        for key in counts:
            counts[key] += int(node.get(key, '0'))
        skipped += [t.get('classname', '')+'.'+t.get('name', '') for t in node.findall('testcase') if t.find('skipped') is not None]
    if counts['tests'] <= counts['skipped'] or counts['failures'] or counts['errors']:
        raise ValueError('Missing or failing Android tests')
    lint = {}
    for variant in ['debug', 'release']:
        issues = ET.parse(root/f'android/app/build/reports/lint-results-{variant}.xml').getroot().findall('issue')
        lint[variant] = {'errors': sum(i.get('severity') in ['Error', 'Fatal'] for i in issues),
                         'warnings': sum(i.get('severity') == 'Warning' for i in issues),
                         'warning_ids': dict(Counter(i.get('id') for i in issues if i.get('severity') == 'Warning'))}
        if lint[variant]['errors']:
            raise ValueError('Lint errors')
    result = {'junit': counts, 'skipped_tests': skipped, 'lint': lint, 'python_tests': python_count(python_report)}
    if pipeline_report:
        result['pipeline_python_tests'] = python_count(pipeline_report)
    return result


def file_names(plan):
    return [plan['apk_name'], 'release-manifest.json', 'INSTALL.md', 'RELEASE_NOTES.md', 'TEST_REPORT.md']


def bundle(directory, plan, cert, tools, pipeline_sha=None, jar=None, allow_test=False):
    expected = set(file_names(plan) + ['SHA256SUMS'])
    if {p.name for p in directory.iterdir()} != expected or any(p.is_symlink() or not p.is_file() for p in directory.iterdir()):
        raise ValueError('Unexpected release asset set')
    lines = (directory/'SHA256SUMS').read_text().splitlines()
    wanted = [f'{sha256(directory/name)}  {name}' for name in file_names(plan)]
    if lines != wanted:
        raise ValueError('Checksums or checksum inventory differ')
    manifest = json.loads((directory/'release-manifest.json').read_text())
    for key in ['source_commit', 'package', 'version_name', 'version_code', 'tag', 'apk_name']:
        if manifest.get(key) != plan[key]:
            raise ValueError('Manifest identity differs: ' + key)
    if manifest.get('signer_certificate_sha256') != fingerprint(cert):
        raise ValueError('Manifest certificate differs')
    if pipeline_sha and manifest.get('pipeline_commit') != pipeline_sha:
        raise ValueError('Manifest pipeline provenance differs')
    apk = directory/plan['apk_name']
    if manifest.get('apk_sha256') != sha256(apk) or manifest.get('apk_size') != apk.stat().st_size:
        raise ValueError('Manifest APK digest differs')
    if manifest.get('schema') != 2 or manifest.get('signing_mode') != 'owner-local':
        raise ValueError('Local signing provenance required')
    if type(manifest.get('test_only')) is not bool or (manifest['test_only'] and not allow_test):
        raise ValueError('Disposable test signatures may never be published')
    from handoff import timestamp
    timestamp(manifest['key_backup_verified_at'])
    if jar:
        from local_sign import verify_signature
        actual_sha1=verify_signature(apk,jar,cert,allow_test=allow_test)
    else:
        actual_sha1=verify_apk(apk,plan,cert,tools)
    if manifest.get('signer_certificate_sha1') != actual_sha1:
        raise ValueError('Manifest SHA-1 differs from actual certificate')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['evidence', 'bundle'])
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--source', type=Path)
    parser.add_argument('--python-report', type=Path)
    parser.add_argument('--pipeline-report', type=Path)
    parser.add_argument('--tools', type=Path)
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text())
    if args.command == 'evidence':
        actual = subprocess.check_output(['git', '-C', str(args.source), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != plan['source_commit']:
            raise ValueError('Wrong source commit')
        subprocess.run(['git', '-C', str(args.source), 'diff', '--exit-code', 'HEAD'], check=True, stdout=subprocess.DEVNULL)
        data = evidence(args.source, args.python_report, args.pipeline_report)
        data['source_commit'] = actual
        (args.directory/'build-evidence.json').write_text(json.dumps(data, indent=2)+'\n')
        return
    cert = fingerprint(os.environ.get('FIELD_RELEASE_CERT_SHA256'))
    bundle(args.directory, plan, cert, args.tools, os.environ.get('GITHUB_SHA'))


if __name__ == '__main__':
    main()
