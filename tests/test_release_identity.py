import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('release_verifier',Path(__file__).resolve().parents[1]/'scripts/verify-alpha3.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
class CertificateTest(unittest.TestCase):
    def good(self,prefix='Signer #1'):
        return f'Number of signers: 1\n{prefix} certificate SHA-256 digest: {v.CERT256}\n{prefix} certificate SHA-1 digest: {v.CERT1}'
    def test_common_formats(self):
        for prefix in ['Signer #1','V2 Signer:','V3 Signer:']:v.verify_certificate(self.good(prefix))
        v.verify_certificate(self.good()+'\n'+self.good('V3 Signer:').split('\n',1)[1])
    def test_fail_closed(self):
        for text in ['',self.good().replace('Number of signers: 1','Number of signers: 2'),
                     self.good().replace('certificate SHA-256','public key SHA-256'),
                     self.good().replace(v.CERT256,'0'*64),self.good().replace(v.CERT1,'invalid'),
                     self.good()+'\nNumber of signers: 1',
                     self.good()+'\nV3 Signer: certificate SHA-256 digest: '+'0'*64]:
            with self.assertRaises(ValueError):v.verify_certificate(text)
