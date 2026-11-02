#!/usr/bin/env python3
"""Read-only device checks plus a disposable signing test. No production identity or publication."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import platform
import pwd
import shutil
import subprocess
import sys
import tempfile
from handoff import ROOT,check_input,plan
from owner_key import external
from portable_tools import java,verify_jar


def runtime_info():
    env=os.environ.copy()
    for n in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:env.pop(n,None)
    r=subprocess.run([java(),'-XshowSettings:properties','-version'],capture_output=True,text=True,env=env)
    if r.returncode:raise RuntimeError('Cannot inspect selected Java runtime')
    result={}
    for line in r.stderr.splitlines():
        key,sep,value=line.strip().partition(' = ')
        if sep and key in ['java.version','java.runtime.version','java.vendor','os.arch']:result[key]=value
    if not result.get('java.version'):raise ValueError('Missing Java version')
    result['major']=int(result['java.version'].split('.')[0])
    return result


def device_info():
    model=Path('/proc/device-tree/model')
    model=model.read_text().rstrip('\x00\n') if model.exists() else None
    return {'hostname':platform.node(),'user':pwd.getpwuid(os.getuid()).pw_name,
            'architecture':platform.machine(),'system':platform.system(),'device_model':model,
            'physical_pi_observed':bool(model and 'Raspberry Pi 5' in model and platform.machine()=='aarch64'),
            'python':platform.python_version()}


def write_record(path,data):
    with Path(path).open('x',encoding='utf-8') as f:
        os.chmod(path,0o600)
        json.dump(data,f,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['input','jar','report']:p.add_argument('--'+n,type=Path,required=True)
    p.add_argument('--pipeline-sha',required=True)
    p.add_argument('--build-run',required=True)
    p.add_argument('--unsigned-sha256',required=True)
    a=p.parse_args();os.umask(0o077)
    output=external(a.report)
    if output.exists():raise ValueError('Refusing to replace an existing preflight report')
    d=device_info();j=runtime_info();tool=verify_jar(a.jar)
    if j['major'] not in [17,21]:raise ValueError('Only tested JDK 17/21 are supported')
    if sys.version_info<(3,11):raise ValueError('Python 3.11+ required')
    check_input(a.input,plan(),a.pipeline_sha,a.build_run,a.unsigned_sha256)
    with tempfile.TemporaryDirectory(prefix='fortgps-preflight-',dir=output.parent) as temp:
        report=Path(temp)/'test.json'
        subprocess.run([sys.executable,str(ROOT/'scripts/release/integration_signing.py'),
                        '--input',str(a.input),'--jar',str(a.jar),'--report',str(report)],check=True)
        crypto=json.loads(report.read_text())
    if not crypto['private_test_files_removed']:raise ValueError('Test-key cleanup incomplete')
    record={'schema':1,'kind':'fortgps-pi-preflight','checked_at':datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'device':d,'java':j,'apksigner_jar_sha256':tool,'crypto':crypto,
            'unsigned_sha256':a.unsigned_sha256,'pipeline_commit':a.pipeline_sha,'run_id':a.build_run,
            'free_bytes':shutil.disk_usage(output.parent).free,'production_identity_created':False}
    write_record(output,record)
    print('Disposable signing preflight complete. Physical Pi observed:',d['physical_pi_observed'])
    print('No production key, service/configuration change or network upload performed.')

if __name__=='__main__':
    try:main()
    except (ValueError,RuntimeError,OSError) as e:sys.exit(str(e))
