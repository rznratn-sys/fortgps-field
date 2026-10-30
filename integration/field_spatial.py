"""Read-only Field -> existing SpatialCatalog adapter. No catalog imports or rebuilds."""
from __future__ import annotations
import argparse
import hashlib
import importlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import uuid
from datetime import datetime

VERSION = "field-spatial-0.1.0"

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""): h.update(b)
    return h.hexdigest()

def utc(value):
    d = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if d.tzinfo is None or d.utcoffset().total_seconds() != 0:
        raise ValueError("Explicit UTC time required")
    return d

def load_field(raw: bytes):
    r = json.loads(raw, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))
    if r.get("schema_version") != "2.0":
        # Preserve old meaning: a web observer position is NOT a surveyed object.
        ident = str(uuid.UUID(r["id"]))
        if ident != r["id"]: raise ValueError("Noncanonical UUID")
        return {"session_id": ident, "revision": 1, "schema_version": "1.1", "session_status": "legacy_unverified", "measurements": [], "warnings": ["legacy_device_position_not_object_survey"]}
    schema = json.loads((Path(__file__).resolve().parents[1] / "schemas/field-session-2.0.schema.json").read_text())
    import jsonschema
    jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(r)
    if str(uuid.UUID(r["session_id"])) != r["session_id"]: raise ValueError("Noncanonical UUID")
    ids = set()
    for m in r["measurements"]:
        if m["session_id"] != r["session_id"]: raise ValueError("Measurement belongs to another session")
        if m["measurement_id"] in ids: raise ValueError("Duplicate measurement UUID")
        ids.add(m["measurement_id"])
        utc(m["measured_at"]); utc(m["created_at"])
        if (m["altitude_m"] is None) != (m["altitude_reference"] is None): raise ValueError("Height/reference mismatch")
    for p in r["photo_associations"]:
        if p["session_id"] != r["session_id"]: raise ValueError("Photo belongs to another session")
    for key in ("created_at", "updated_at", "started_at", "ended_at"):
        if r.get(key) is not None: utc(r[key])
    if r.get("started_at") is not None and r.get("ended_at") is not None:
        if utc(r["ended_at"]) < utc(r["started_at"]) and "clock_order_conflict" not in r["warnings"]:
            r["warnings"].append("clock_order_conflict")
    return r

def process(raw: bytes, catalog, catalog_sha: str):
    field = load_field(raw)
    output = {"schema_version": "field-spatial/1", "adapter_version": VERSION,
        "source_field_sha256": hashlib.sha256(raw).hexdigest(), "session_id": field["session_id"],
        "field_revision": field["revision"], "catalog_sha256": catalog_sha,
        "catalog_run_id": catalog.run["run_id"], "catalog_module_version": catalog.run["module_version"],
        "terrain_run_id": getattr(catalog, "terrain_run", {}).get("terrain_run_id"),
        "identity_confirmed": False, "evidence_class": "computed_candidate_relationships",
        "policy": {"radius_m": 1000, "limit_per_measurement": 10, "automatic_merge": False,
            "height_comparison": "disabled_device_height_is_not_dem_terrain", "raw_field_modified": False},
        "measurement_results": [], "photo_associations": field.get("photo_associations", []),
        "warnings": field.get("warnings", [])}
    if field["session_status"] in ("open", "cancelled"):
        output["processing_status"] = "not_final_or_cancelled"; return output
    surveys = [m for m in field["measurements"] if m["measurement_role"] == "object_survey"]
    if not surveys:
        output["processing_status"] = "no_object_survey"; return output
    from spatial_regions import region_geometry
    from shapely.geometry import Point
    populations = {r[0] for r in catalog.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for m in surveys:
        # Existing area envelopes are reused. They are not political boundaries.
        covered = any(region_geometry(a).covers(Point(m["longitude"], m["latitude"])) for a in catalog.config["areas"])
        entry = {"measurement_id": m["measurement_id"], "position_relation": m["position_relation"],
            "horizontal_accuracy_m": m["horizontal_accuracy_m"], "candidates": []}
        if not covered:
            entry["status"] = "outside_selected_catalog_coverage_no_fallback"
        else:
            kw = {"radius_m": 1000, "limit": 10, "allow_partial": False}
            if "spatial_populations" in populations: kw["population"] = "structures"
            result = catalog.nearby(m["latitude"], m["longitude"], **kw)
            entry["coverage"] = result["coverage"]
            entry["coverage_boundary"] = "existing_catalog_area_envelope_not_verified_state_border"
            for r in result["results"]:
                t = r.get("terrain") or {}
                entry["candidates"].append({"object_uid": r["object_uid"], "object_version_id": r["object_version_id"],
                    "name": r["name"], "classification": json.loads(r["classification_json"]),
                    "distance_m": r["distance_m"], "azimuth_deg": r["azimuth_deg"],
                    "representative_method": r["representative_method"], "source_kind": r["source_kind"],
                    "source_key": r["source_key"], "source_snapshot_id": r["source_snapshot_id"],
                    "source_catalog_id": r["source_catalog_id"], "terrain_elevation_m": t.get("elevation_m"),
                    "terrain_vertical_crs": t.get("vertical_crs"), "terrain_status": t.get("status"),
                    "delta_z_m": None, "height_comparison_status": "device_vs_terrain_incompatible",
                    "identity_confirmed": False})
            entry["status"] = "candidates_require_review" if entry["candidates"] else "potential_new_object_or_catalog_gap"
        output["measurement_results"].append(entry)
    output["processing_status"] = "processed"
    return output

def write_once(path, data):
    path = Path(path)
    content = json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False).encode() + b"\n"
    if path.exists():
        if path.read_bytes() != content: raise ValueError("Output already exists with different content")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".field-")
    try:
        with os.fdopen(fd, "wb") as f: f.write(content); f.flush(); os.fsync(f.fileno())
        # Atomic create, never overwrite an existing sidecar.
        try: os.link(tmp, path)
        except FileExistsError:
            if path.read_bytes() != content: raise ValueError("Concurrent output conflict")
    finally: os.unlink(tmp)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--field", required=True, type=Path)
    p.add_argument("--spatial-code", required=True, type=Path)
    p.add_argument("--catalog", required=True, type=Path)
    p.add_argument("--catalog-sha256", required=True)
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()
    if args.out.resolve() in (args.field.resolve(), args.catalog.resolve()): p.error("Output must be separate")
    if sha(args.catalog) != args.catalog_sha256: p.error("Catalog SHA-256 mismatch")
    # Reuse the published module and its R-tree/geodesic routines without copying them.
    sys.path.insert(0, str(args.spatial_code.resolve()))
    from terrain_catalog import TerrainCatalog
    cat = TerrainCatalog(args.catalog)
    try: result = process(args.field.read_bytes(), cat, args.catalog_sha256)
    finally: cat.close()
    write_once(args.out, result)
    print(json.dumps({"status": result["processing_status"], "output": str(args.out), "adapter_version": VERSION}))

if __name__ == "__main__": main()
