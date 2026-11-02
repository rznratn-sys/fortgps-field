#!/usr/bin/env python3
"""Public, architecture independent handoff validation. No key access or network."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SIGNATURE_FILES = {'META-INF/MANIFEST.MF', 'META-INF/FIELD.SF', 'META-INF/FIELD.RSA'}
INPUT_FILES = {'unsigned.apk', 'handoff.json', 'INSTALL.md', 'RELEASE_NOTES.md'}

def sha(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def digest(value):
    if not re.fullmatch('[0-9a-f]{64}', value or ''):
        raise ValueError('Expected lowercase SHA-256')
    return value

def commit(value):
    if not re.fullmatch('[0-9a-f]{40}', value or ''):
        raise ValueError('Expected full commit SHA')
    return value

def timestamp(value):
    date = datetime.strptime(value, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
    if date > datetime.now(timezone.utc):
        raise ValueError('Future backup attestation')
    return value

def plan(path=ROOT/'scripts/release/plan.json'):
    p = json.loads(Path(path).read_text())
    commit(p['source_commit'])
    if p['repository'] != 'rznratn-sys/fortgps-field' or p['package'] != 'cz.fortgps.field':
        raise ValueError('Unexpected repository/package')
    if not re.fullmatch(r'2\.\d+\.\d+(?:-[A-Za-z0-9.]+)?', p['version_name']):
        raise ValueError('Invalid version')
    if p['tag'] != 'v'+p['version_name'] or p['apk_name'] != 'FortGPS_Field_'+p['version_name']+'.apk':
        raise ValueError('Inconsistent release names')
    if type(p['version_code']) is not int or p['version_code'] < 1:
        raise ValueError('Invalid version code')
    return p

def inventory(apk):
    items = {}
    total = 0
    with zipfile.ZipFile(apk) as z:
        for i in z.infolist():
            if i.filename in items or i.filename.startswith('/') or '..' in Path(i.filename).parts or '\\' in i.filename:
                raise ValueError('Duplicate/unsafe ZIP entry')
            total += i.file_size
            if total > 512*1024*1024 or i.file_size > 128*1024*1024 or i.flag_bits & 1:
                raise ValueError('Encrypted or oversized APK ZIP')
            with z.open(i) as f:
                checksum = hashlib.file_digest(f, 'sha256').hexdigest()
            items[i.filename] = {'sha256':checksum, 'size':i.file_size, 'compression':i.compress_type}
    if 'AndroidManifest.xml' not in items or 'classes.dex' not in items:
        raise ValueError('Not an application APK')
    return items

def aligned(apk):
    with zipfile.ZipFile(apk) as z, Path(apk).open('rb') as f:
        for i in z.infolist():
            if i.compress_type != zipfile.ZIP_STORED or i.is_dir():
                continue
            f.seek(i.header_offset)
            header = f.read(30)
            if len(header) != 30 or header[:4] != b'PK\x03\x04':
                raise ValueError('Invalid ZIP local header')
            name, extra = struct.unpack_from('<HH', header, 26)
            offset = i.header_offset+30+name+extra
            boundary = 16384 if i.filename.endswith('.so') else 4
            if offset % boundary:
                raise ValueError('APK alignment invalid')

def unsigned(apk):
    entries = inventory(apk)
    if any(n.upper() in SIGNATURE_FILES or re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', n.upper()) for n in entries):
        raise ValueError('Input already has a JAR signature')
    data = Path(apk).read_bytes()
    end = data.rfind(b'PK\x05\x06', max(0,len(data)-65557))
    if end < 0:
        raise ValueError('Missing ZIP central directory')
    cd = struct.unpack_from('<I', data, end+16)[0]
    if data[max(0,cd-16):cd] == b'APK Sig Block 42':
        raise ValueError('Input already has an APK signing block')
    aligned(apk)
    return entries

def payload_equal(apk, entries):
    actual = inventory(apk)
    if set(actual)-set(entries) != SIGNATURE_FILES or any(actual.get(n) != info for n,info in entries.items()):
        raise ValueError('Signed APK payload differs from approved unsigned APK')
    aligned(apk)

def exact_files(directory, names):
    directory = Path(directory)
    if {p.name for p in directory.iterdir()} != set(names) or any(p.is_symlink() or not p.is_file() for p in directory.iterdir()):
        raise ValueError('Unexpected file set or symlink')

def check_evidence(data):
    j = data['junit']
    if j['failures'] or j['errors'] or j['tests'] <= j['skipped'] or j['skipped'] < 0:
        raise ValueError('Invalid JUnit results')
    if data['python_tests'] <= 0 or data['pipeline_python_tests'] <= 0:
        raise ValueError('Python tests missing')
    if any(data['lint'][v]['errors'] for v in ['debug','release']):
        raise ValueError('Lint errors')

def check_input(directory, p, expected_pipeline, expected_run, expected_sha):
    directory = Path(directory)
    exact_files(directory, INPUT_FILES)
    meta = json.loads((directory/'handoff.json').read_text())
    if meta['schema'] != 1 or meta['plan'] != p or meta['pipeline_commit'] != commit(expected_pipeline):
        raise ValueError('Wrong handoff source/plan/pipeline')
    if str(meta['run_id']) != str(expected_run) or not str(expected_run).isdigit():
        raise ValueError('Wrong build run')
    if meta['unsigned_sha256'] != digest(expected_sha) or sha(directory/'unsigned.apk') != expected_sha:
        raise ValueError('Wrong unsigned APK digest')
    if meta['entries'] != unsigned(directory/'unsigned.apk'):
        raise ValueError('Wrong unsigned payload inventory')
    for name in ['INSTALL.md','RELEASE_NOTES.md']:
        if meta['documents'][name] != sha(directory/name):
            raise ValueError('Changed handoff document')
    check_evidence(meta['build'])
    return meta

def create_input(directory, p, evidence, pipeline, run, attempt):
    directory = Path(directory)
    if evidence.pop('source_commit') != p['source_commit']:
        raise ValueError('Wrong evidence source')
    check_evidence(evidence)
    meta = {'schema':1, 'plan':p, 'pipeline_commit':commit(pipeline), 'run_id':str(run),
            'run_attempt':int(attempt), 'unsigned_sha256':sha(directory/'unsigned.apk'),
            'entries':unsigned(directory/'unsigned.apk'), 'build':evidence,
            'documents':{n:sha(directory/n) for n in ['INSTALL.md','RELEASE_NOTES.md']}}
    (directory/'handoff.json').write_text(json.dumps(meta,indent=2)+'\n')
    check_input(directory,p,pipeline,run,meta['unsigned_sha256'])
    return meta
