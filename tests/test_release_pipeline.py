import copy
import sys
import zipfile
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts/release'))
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT/f'scripts/release/{name}.py')
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj
v = module('verify')
b = module('check_backup')
k = module('owner_key')
h = module('handoff')
ls = module('local_sign')
gh = module('github_io')
PLAN = json.loads((ROOT/'scripts/release/plan.json').read_text())
CERT = 'a'*64
SIGN = f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {CERT}\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
BADGING = "package: name='cz.fortgps.field' versionCode='20002' versionName='2.0.0-alpha3'\n"

class ReleaseTests(unittest.TestCase):
    def test_certificate_good(self):
        v.certificate(SIGN, CERT)
    def test_certificate_rejects_missing_wrong_multiple_and_public_key(self):
        for bad in ['', SIGN.replace(CERT, 'b'*64), SIGN.replace('signers: 1', 'signers: 2'), SIGN.replace('certificate SHA', 'public key SHA'), SIGN+SIGN, SIGN.replace(': true', ': false')]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                v.certificate(bad, CERT)
    def test_fingerprint_fails_closed(self):
        for bad in ['', None, 'A'*64, 'a'*63, 'a'*65, '../cert']:
            with self.assertRaises(ValueError): v.fingerprint(bad)
    def test_identity_and_release_flags(self):
        v.identity(BADGING, PLAN)
        for bad in [BADGING.replace('20002','20001'), BADGING.replace('alpha3','alpha4'), BADGING.replace('cz.fortgps.field','cz.foreign.app'), BADGING+'application-debuggable\n', BADGING+'application-testOnly\n']:
            with self.assertRaises(ValueError): v.identity(bad, PLAN)
    def test_binary_manifest_rejects_debug_or_test(self):
        v.manifest_flags('    A: android:debuggable(0x0101000f)=(type 0x12)0x0')
        for flag in ['debuggable','testOnly']:
            with self.assertRaises(ValueError):
                v.manifest_flags(f'    A: android:{flag}(0x0101000f)=(type 0x12)0xffffffff')
    def test_tool_failure_cannot_be_parsed_as_success(self):
        with patch.object(v.subprocess, 'check_output', side_effect=subprocess.CalledProcessError(1, 'apksigner')):
            with self.assertRaises(subprocess.CalledProcessError): v.verify_apk(Path('apk'), PLAN, CERT, Path('/tools'))
    def test_bundle_rejects_tamper_and_extra_assets(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            for name in v.file_names(PLAN): (p/name).write_text('fixture')
            manifest = dict(PLAN, schema=2, signing_mode='owner-local', test_only=False, key_backup_verified_at='2026-11-02T00:00:00Z', signer_certificate_sha256=CERT, pipeline_commit='c'*40,
                            apk_sha256=v.sha256(p/PLAN['apk_name']), apk_size=7)
            (p/'release-manifest.json').write_text(json.dumps(manifest))
            def sums(): (p/'SHA256SUMS').write_text(''.join(f'{v.sha256(p/n)}  {n}\n' for n in v.file_names(PLAN)))
            sums()
            with patch.object(v, 'verify_apk', return_value=None):
                v.bundle(p, PLAN, CERT, Path('/tools'), 'c'*40)
                (p/'INSTALL.md').write_text('changed')
                with self.assertRaises(ValueError): v.bundle(p, PLAN, CERT, Path('/tools'))
                sums()
                (p/'secret.p12').write_text('forbidden')
                with self.assertRaises(ValueError): v.bundle(p, PLAN, CERT, Path('/tools'))
                (p/'secret.p12').unlink()
                with self.assertRaises(ValueError): v.bundle(p, PLAN, 'b'*64, Path('/tools'))
                with self.assertRaises(ValueError): v.bundle(p, PLAN, CERT, Path('/tools'), 'd'*40)
                manifest['source_commit']='e'*40
                (p/'release-manifest.json').write_text(json.dumps(manifest)); sums()
                with self.assertRaises(ValueError): v.bundle(p, PLAN, CERT, Path('/tools'))
    def test_missing_or_failed_test_reports(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            with self.assertRaises(ValueError): v.evidence(p, p/'python.txt')
            report=p/'android/app/build/test-results/testDebugUnitTest/TEST-example.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="1" failures="1"/>')
            with self.assertRaises(ValueError): v.evidence(p, p/'python.txt')
            report.write_text('<testsuite tests="56" failures="0" errors="0" skipped="3"><testcase classname="Fixture" name="optional"><skipped/></testcase></testsuite>')
            lint=p/'android/app/build/reports'; lint.mkdir(parents=True)
            for variant in ['debug','release']:
                (lint/f'lint-results-{variant}.xml').write_text('<issues><issue severity="Warning" id="GradleDependency"/></issues>')
            (p/'python.txt').write_text('Ran 14 tests in 1s\nOK\n')
            (p/'pipeline.txt').write_text('Ran 19 tests in 1s\nOK\n')
            result=v.evidence(p,p/'python.txt',p/'pipeline.txt')
            self.assertEqual(result['junit']['skipped'],3)
            self.assertEqual(result['skipped_tests'],['Fixture.optional'])
            self.assertEqual(result['pipeline_python_tests'],19)
            self.assertEqual(result['lint']['release']['warning_ids'],{'GradleDependency':1})
            (lint/'lint-results-release.xml').write_text('<issues><issue severity="Error" id="Fail"/></issues>')
            with self.assertRaises(ValueError): v.evidence(p,p/'python.txt')
    def test_no_cloud_signing_secrets_or_owner_upload_command(self):
        text=(ROOT/'.github/workflows/publish-alpha3.yml').read_text()
        self.assertNotIn('secrets.',text)
        self.assertFalse((ROOT/'scripts/release/sign.sh').exists())
        self.assertNotIn('gh', (ROOT/'scripts/release/local_sign.py').read_text().split('def main():')[0].replace('SHA-256',''))
        self.assertFalse(hasattr(k,'upload'))
    def test_keystore_inside_repository_forbidden(self):
        with self.assertRaises(ValueError): k.external(ROOT/'test.p12')
    def test_private_subprocess_errors_suppressed(self):
        with patch.object(k.subprocess, 'run', return_value=subprocess.CompletedProcess([],1,b'secret',b'password')):
            with self.assertRaises(RuntimeError) as cm: k.run(['keytool'])
            self.assertNotIn('password',str(cm.exception)); self.assertNotIn('secret',str(cm.exception))
    def test_backup_same_file_and_modified_backup_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d); key=p/'key'; key.write_bytes(b'private fixture, not a key')
            with self.assertRaises(ValueError): k.backup_record(key,key,'alias','password',p/'receipt')
            restored=p/'restored'; restored.write_bytes(b'different')
            with self.assertRaises(ValueError): k.backup_record(key,restored,'alias','password',p/'receipt')
    def test_backup_json_duplicate_properties_and_nonfinite_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'backup'
            for content in ['{"sessions":[],"sessions":[]}', '{"format":"FortGPS Field 2.0 backup","sessions":[NaN]}']:
                p.write_text(content)
                with self.assertRaises(ValueError): b.load(p)
    def test_backup_comparison_preserves_payloads(self):
        before=[{'session_id':'uuid', 'revision':1, 'xmp_result':{'state':'verified'}}]
        after=copy.deepcopy(before); after[0]['photo_boundary_policy']=None
        b.compare(before,after)
        for field,value in [('revision',2),('xmp_result',{'state':'failed'})]:
            changed=copy.deepcopy(after); changed[0][field]=value
            with self.assertRaises(ValueError): b.compare(before,changed)
    def test_next_release_keeps_certificate_gate(self):
        alpha4=dict(PLAN,version_name='2.0.0-alpha4',version_code=20003)
        v.identity(BADGING.replace('alpha3','alpha4').replace('20002','20003'),alpha4)
        v.certificate(SIGN,CERT)
    def test_workflow_trust_boundaries(self):
        text=(ROOT/'.github/workflows/publish-alpha3.yml').read_text()
        self.assertNotIn('pull_request_target',text)
        self.assertNotIn('secrets.',text)
        self.assertIn("github.ref == 'refs/heads/main'",text)
        self.assertIn('environment: field-publication',text)
        self.assertIn("vars.FIELD_PUBLICATION_ENABLED == 'true'",text)
        self.assertIn('ubuntu-24.04-arm',text)
        self.assertNotIn('self-hosted',text)
        import re
        actions=re.findall(r'uses: ([^\s]+)',text)
        self.assertTrue(all(re.fullmatch(r'[^@]+@[0-9a-f]{40}',a) for a in actions))
        publish=(ROOT/'scripts/release/publish.sh').read_text()
        self.assertNotIn('--clobber',publish)
        self.assertLess(publish.index('validate_publication.py'),publish.index('--method PATCH'))


class BackupValidationTests(unittest.TestCase):
    def session(self):
        return {'session_id':'00000000-0000-4000-8000-000000000001','session_status':'open',
                'measurements':[{'measurement_id':'00000000-0000-4000-8000-000000000002',
                                 'session_id':'00000000-0000-4000-8000-000000000001'}], 'photo_associations':[]}
    def test_foreign_duplicate_and_multiple_open_sessions(self):
        original=self.session()
        self.assertEqual(b.validate([original],{'type':'object'})['measurements'],1)
        with self.assertRaises(ValueError): b.validate([original,original],{'type':'object'})
        bad=copy.deepcopy(original); bad['measurements'][0]['session_id']='foreign'
        with self.assertRaises(ValueError): b.validate([bad],{'type':'object'})
        bad=copy.deepcopy(original); bad['measurements']*=2
        with self.assertRaises(ValueError): b.validate([bad],{'type':'object'})
        bad=copy.deepcopy(original); bad['session_id']='00000000-0000-4000-8000-000000000003'; bad['measurements']=[]
        with self.assertRaises(ValueError): b.validate([original,bad],{'type':'object'})
    def test_schema_failures_do_not_echo_private_payload(self):
        bad=self.session(); bad['private_note']='SENSITIVE_COORDINATES'
        with self.assertRaises(ValueError) as cm: b.validate([bad],{'required':['absent']})
        self.assertNotIn('SENSITIVE_COORDINATES',str(cm.exception))

class PortableHandoffTests(unittest.TestCase):
    def fixture(self,root):
        with zipfile.ZipFile(root/'unsigned.apk','w',compression=zipfile.ZIP_DEFLATED) as z:
            z.writestr('AndroidManifest.xml',b'opaque-unit-fixture')
            z.writestr('classes.dex',b'opaque-unit-fixture')
        for name in ['INSTALL.md','RELEASE_NOTES.md']:(root/name).write_text('public fixture')
        evidence={'source_commit':PLAN['source_commit'],'junit':{'tests':56,'failures':0,'errors':0,'skipped':3},
                  'python_tests':14,'pipeline_python_tests':1,'lint':{'debug':{'errors':0},'release':{'errors':0}}}
        return h.create_input(root,PLAN,evidence,'c'*40,'123',1)
    def test_input_bound_to_commit_run_and_independent_hash(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);m=self.fixture(p)
            h.check_input(p,PLAN,'c'*40,'123',m['unsigned_sha256'])
            for pipeline,run,checksum in [('d'*40,'123',m['unsigned_sha256']),('c'*40,'124',m['unsigned_sha256']),('c'*40,'123','0'*64)]:
                with self.assertRaises(ValueError):h.check_input(p,PLAN,pipeline,run,checksum)
            (p/'INSTALL.md').write_text('modified')
            with self.assertRaises(ValueError):h.check_input(p,PLAN,'c'*40,'123',m['unsigned_sha256'])
    def test_extra_private_file_or_symlink_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);m=self.fixture(p)
            (p/'private.p12').write_text('not a real key')
            with self.assertRaises(ValueError):h.check_input(p,PLAN,'c'*40,'123',m['unsigned_sha256'])
            (p/'private.p12').unlink();(p/'INSTALL.md').unlink();(p/'INSTALL.md').symlink_to(p/'RELEASE_NOTES.md')
            with self.assertRaises(ValueError):h.check_input(p,PLAN,'c'*40,'123',m['unsigned_sha256'])
    def test_duplicate_zip_and_signature_inputs_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);self.fixture(p)
            with zipfile.ZipFile(p/'unsigned.apk','a') as z:z.writestr('META-INF/FIELD.RSA',b'fixture')
            with self.assertRaises(ValueError):h.unsigned(p/'unsigned.apk')
            with zipfile.ZipFile(p/'duplicate.apk','w') as z:
                z.writestr('../escape',b'fixture')
            with self.assertRaises(ValueError):h.inventory(p/'duplicate.apk')
    def test_only_signature_files_may_be_added(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);m=self.fixture(p);signed=p/'signed.apk'
            import shutil
            shutil.copyfile(p/'unsigned.apk',signed)
            with zipfile.ZipFile(signed,'a',compression=zipfile.ZIP_DEFLATED) as z:
                for n in h.SIGNATURE_FILES:z.writestr(n,b'fixture')
            h.payload_equal(signed,m['entries'])
            with zipfile.ZipFile(signed,'a',compression=zipfile.ZIP_DEFLATED) as z:z.writestr('assets/unapproved',b'bad')
            with self.assertRaises(ValueError):h.payload_equal(signed,m['entries'])
    def test_alpha4_uses_same_handoff_and_identity_contract(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);p4=dict(PLAN,version_name='2.0.0-alpha4',version_code=20003,tag='v2.0.0-alpha4',apk_name='FortGPS_Field_2.0.0-alpha4.apk')
            (p/'plan.json').write_text(json.dumps(p4));self.assertEqual(h.plan(p/'plan.json'),p4)
            v.certificate(SIGN,CERT)
    def test_no_tests_or_future_backup_attestation_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'report';p.write_text('Ran 0 tests in 0s\nOK\n')
            with self.assertRaises(ValueError):v.python_count(p)
        with self.assertRaises(ValueError):h.timestamp('2999-01-01T00:00:00Z')
    def test_github_transport_fails_closed_on_wrong_run(self):
        value={'repository':{'full_name':PLAN['repository']},'head_sha':'c'*40,'head_branch':'main',
               'event':'workflow_dispatch','conclusion':'success','path':'.github/workflows/publish-alpha3.yml'}
        with patch.object(gh,'api',return_value=value):gh.build_run('123','c'*40)
        for field,bad in [('head_branch','feature'),('event','pull_request'),('conclusion','failure'),('head_sha','d'*40)]:
            changed=dict(value);changed[field]=bad
            with patch.object(gh,'api',return_value=changed),self.assertRaises(ValueError):gh.build_run('123','c'*40)
    def test_test_signed_bundle_publication_refused(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            for n in v.file_names(PLAN):(p/n).write_text('fixture')
            manifest=dict(PLAN,schema=2,signing_mode='owner-local',test_only=True,signer_certificate_sha256=CERT,
                          apk_sha256=v.sha256(p/PLAN['apk_name']),apk_size=7,key_backup_verified_at='2026-11-02T00:00:00Z')
            (p/'release-manifest.json').write_text(json.dumps(manifest))
            (p/'SHA256SUMS').write_text(''.join(f'{v.sha256(p/n)}  {n}\n' for n in v.file_names(PLAN)))
            with self.assertRaisesRegex(ValueError,'test signatures'):v.bundle(p,PLAN,CERT,None)

if __name__ == '__main__':unittest.main()
