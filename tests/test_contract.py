import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("field_spatial", ROOT / "integration/field_spatial.py")
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

class ContractTest(unittest.TestCase):
    def fixture(self): return json.loads((ROOT / "tests/fixtures/session-2.0.json").read_text())
    def test_schema_fixture(self): self.assertEqual("standalone", mod.load_field(json.dumps(self.fixture()).encode())["session_status"])
    def test_missing_height_stays_null(self): self.assertIsNone(mod.load_field(json.dumps(self.fixture()).encode())["measurements"][0]["altitude_m"])
    def test_foreign_session_rejected(self):
        r = self.fixture(); r["measurements"][0]["session_id"] = "12345678-1234-4234-8234-123456789abd"
        with self.assertRaises(ValueError): mod.load_field(json.dumps(r).encode())
    def test_invalid_coordinate_rejected(self):
        r = self.fixture(); r["measurements"][0]["latitude"] = 91
        with self.assertRaises(Exception): mod.load_field(json.dumps(r).encode())
    def test_time_without_timezone_rejected(self):
        r = self.fixture(); r["measurements"][0]["measured_at"] = "2026-01-01T10:00:00"
        with self.assertRaises(Exception): mod.load_field(json.dumps(r).encode())
    def test_inconsistent_time_flagged(self):
        r = self.fixture(); r["ended_at"] = "2026-01-01T09:00:00Z"
        self.assertIn("clock_order_conflict", mod.load_field(json.dumps(r).encode())["warnings"])
    def test_photo_confidence_not_fabricated(self):
        r = self.fixture(); r["photo_associations"] = [{"session_id":r["session_id"],"photo_reference":{"sha256":"a"*64,"size_bytes":123,"original_name":None,"hash_scope":"android_picker_delivered_bytes","original_identity_verified":False},"association_method":"user_photo_picker","association_status":"user_confirmed","association_confidence":None}]
        self.assertIsNone(mod.load_field(json.dumps(r).encode())["photo_associations"][0]["association_confidence"])
    def test_bad_photo_reference_rejected(self):
        r = self.fixture(); r["photo_associations"] = [{"session_id":r["session_id"],"photo_reference":{"sha256":"not-a-hash"}}]
        with self.assertRaises(Exception): mod.load_field(json.dumps(r).encode())
    def test_legacy_does_not_create_object_survey(self):
        r = {"id":"12345678-1234-4234-8234-123456789abc","latitude":50,"longitude":16}
        self.assertEqual([], mod.load_field(json.dumps(r).encode())["measurements"])
    def test_atomic_idempotent_sidecar_conflict(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)/"sidecar.json"; mod.write_once(p,{"test":1}); before = p.read_bytes(); mod.write_once(p,{"test":1})
            self.assertEqual(before,p.read_bytes())
            with self.assertRaises(ValueError): mod.write_once(p,{"test":2})

if __name__ == "__main__": unittest.main()
