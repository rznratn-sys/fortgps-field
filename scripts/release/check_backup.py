#!/usr/bin/env python3
"""Offline JSON preflight; never uploads data or proves a full database backup."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import uuid


def strict_pairs(items):
    data = {}
    for key, value in items:
        if key in data:
            raise ValueError('Duplicate JSON property')
        data[key] = value
    return data


def load(path):
    if path.stat().st_size > 20*1024*1024:
        raise ValueError('Backup exceeds alpha3 import limit (20 MiB)')
    def bad_constant(value):
        raise ValueError('Nonfinite JSON number')
    obj = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=strict_pairs, parse_constant=bad_constant)
    if not isinstance(obj, dict) or obj.get('format') != 'FortGPS Field 2.0 backup' or not isinstance(obj.get('sessions'), list):
        raise ValueError('Expected native Field 2.0 backup envelope')
    return obj['sessions']


def validate(sessions, schema):
    from jsonschema import Draft202012Validator, FormatChecker
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    ids, measurements = set(), set()
    open_count = 0
    for s in sessions:
        if list(validator.iter_errors(s)):
            raise ValueError('A session fails the alpha3 JSON schema (private details suppressed)')
        sid = s['session_id']
        if str(uuid.UUID(sid)) != sid or sid in ids:
            raise ValueError('Invalid/duplicate session UUID')
        ids.add(sid)
        open_count += s['session_status'] == 'open'
        for m in s['measurements']:
            mid = m['measurement_id']
            if m['session_id'] != sid or str(uuid.UUID(mid)) != mid or mid in measurements:
                raise ValueError('Invalid/duplicate measurement UUID or foreign session')
            measurements.add(mid)
        if any(p['session_id'] != sid for p in s['photo_associations']):
            raise ValueError('Foreign photo association')
    if open_count > 1:
        raise ValueError('More than one open session; alpha3 import would reject it')
    return {'sessions': len(ids), 'measurements': len(measurements), 'open_sessions': open_count,
            'xmp_states': dict(Counter((s.get('xmp_result') or {}).get('state', 'none') for s in sessions))}


def canonical(s):
    # Alpha3 introduces these nullable fields; alpha2 may omit them.
    s = dict(s)
    for key in ['photo_clock_epoch_start', 'photo_clock_epoch_end', 'photo_boundary_policy',
                'photo_started_elapsed_nanos', 'photo_ended_elapsed_nanos']:
        s.setdefault(key, None)
    return json.dumps(s, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def compare(before, after):
    if {s['session_id']: canonical(s) for s in before} != {s['session_id']: canonical(s) for s in after}:
        raise ValueError('Session payloads differ; keep sync disabled and inspect private backups')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('backup', type=Path)
    p.add_argument('--schema', type=Path, required=True)
    p.add_argument('--compare', type=Path, help='New alpha3 export to compare against original alpha2')
    a = p.parse_args()
    schema = json.loads(a.schema.read_text())
    sessions = load(a.backup)
    summary = validate(sessions, schema)
    if a.compare:
        after = load(a.compare)
        validate(after, schema)
        compare(sessions, after)
        summary['payloads_equal'] = True
    summary['backup_sha256'] = hashlib.sha256(a.backup.read_bytes()).hexdigest()
    print(json.dumps(summary, indent=2))
    print('JSON preflight only. This does not restore outbox history, JPEG journals, photos or OAuth.')


if __name__ == '__main__':
    main()
