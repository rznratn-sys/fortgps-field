import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT/f'scripts/release/{name}.py')
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj
v = module('verify')
b = module('check_backup')
k = module('owner_key')
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
            manifest = dict(PLAN, signer_certificate_sha256=CERT, pipeline_commit='c'*40,
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
    def test_signing_missing_secrets_exits_without_files(self):
        env={key: value for key,value in os.environ.items() if not key.startswith('FIELD_')}
        with tempfile.TemporaryDirectory() as d:
            env['RUNNER_TEMP']=d
            result=subprocess.run(['bash', str(ROOT/'scripts/release/sign.sh')], env=env, capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(list(Path(d).iterdir()),[])
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
        self.assertNotIn('secrets.', text.split('  sign:')[0])
        self.assertIn("github.ref == 'refs/heads/main'",text)
        self.assertIn('environment: field-release',text)
        signing=text.split('  sign:')[1].split('  publish:')[0]
        self.assertNotIn('./gradlew', signing)
        self.assertNotIn('contents: write', signing)
        import re
        actions=re.findall(r'uses: ([^\s]+)',text)
        self.assertTrue(all(re.fullmatch(r'[^@]+@[0-9a-f]{40}',a) for a in actions))
        publish=(ROOT/'scripts/release/publish.sh').read_text()
        self.assertNotIn('--clobber',publish)
        self.assertLess(publish.index('draft-readback --tools'),publish.index('--draft=false'))


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

class OwnerEnvironmentTests(unittest.TestCase):
    def environment(self):
        return {'can_admins_bypass':False,'deployment_branch_policy':{'custom_branch_policies':True},
                'protection_rules':[{'type':'required_reviewers','reviewers':[{'id':1}],'prevent_self_review':True}]}
    def test_environment_requires_review_and_only_main(self):
        env=self.environment()
        branches={'branch_policies':[{'name':'main','type':'branch'}]}
        with patch.object(k,'run', side_effect=[json.dumps(env).encode(),json.dumps(branches).encode()]): k.check_environment()
        for change in ['bypass','review','branch','tag']:
            e=copy.deepcopy(env); br=copy.deepcopy(branches)
            if change=='bypass': e['can_admins_bypass']=True
            if change=='review': e['protection_rules']=[]
            if change=='branch': br['branch_policies'][0]['name']='*'
            if change=='tag': br['branch_policies'][0]['type']='tag'
            with self.subTest(change=change), patch.object(k,'run',side_effect=[json.dumps(e).encode(),json.dumps(br).encode()]):
                with self.assertRaises(ValueError): k.check_environment()

if __name__ == "__main__": unittest.main()
