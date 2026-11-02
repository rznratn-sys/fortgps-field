#!/usr/bin/env python3
"""Extract only architecture-neutral apksigner.jar; no SDK/native tool installation."""
import argparse
import json
import os
from pathlib import Path
import zipfile
from handoff import ROOT, digest, sha

def java():
    home = os.environ.get('FORTGPS_JAVA_HOME')
    return str(Path(home)/'bin/java') if home else 'java'

def java_command(jar, *args):
    return [java(), '-Xms16m', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
            '-jar', str(jar), *args]

def verify_jar(path):
    lock = json.loads((ROOT/'scripts/release/tools-lock.json').read_text())
    expected = digest(lock['apksigner_jar_sha256'])
    if Path(path).is_symlink() or sha(path) != expected:
        raise ValueError('Unapproved apksigner JAR')
    return expected

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--sdk-archive',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    with zipfile.ZipFile(a.sdk_archive) as z:
        entries=[i for i in z.infolist() if i.filename.endswith('/lib/apksigner.jar')]
        if len(entries)!=1 or entries[0].file_size > 16*1024*1024:
            raise ValueError('Expected one Build Tools apksigner JAR')
        data=z.read(entries[0])
    a.output.mkdir(mode=0o700)
    jar=a.output/'apksigner.jar'
    try:
        jar.write_bytes(data)
        print('Verified apksigner JAR SHA-256:',verify_jar(jar))
    except Exception:
        jar.unlink(missing_ok=True);a.output.rmdir();raise

if __name__=='__main__':main()
