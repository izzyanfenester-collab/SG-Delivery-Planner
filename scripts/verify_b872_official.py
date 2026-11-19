#!/usr/bin/env python3
"""Verify the owner-approved B872 Official APK and retain honest acceptance evidence."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from restore_b871_assets872 import BASELINE_SHA256

APPROVED_TESTING = "d44b460e6725fe0d36dfda2ffab1930220ba164f"
BASELINE_COMMIT = "dd368b27ef30fccadbfc62ab69ceed2aeb0c770f"
ORIGINAL_CERT = "b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99"

baseline, official, output = map(Path, sys.argv[1:4])
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256
output.mkdir(parents=True, exist_ok=True)
signing = (output / "signing-verification.txt").read_text()
assert "Signer #1 certificate SHA-256 digest: " + ORIGINAL_CERT in signing
with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(official) as new:
    assert new.testzip() is None
    names = [n for n in old.namelist() if n.startswith("assets/voicepacks/") and not n.endswith("/")]
    for name in names:
        assert new.read(name) == old.read(name), name
    assert len([n for n in names if n.endswith(".mp3")]) == 209
    for name in ("assets/sg_enforcement_cameras.json", "assets/sg_speed_camera_reference_867.json",
                 "assets/sg_speed_limits_reference_868.csv"):
        assert new.read(name) == old.read(name), name
    snapshot = json.loads(new.read("assets/sg_enforcement_cameras.json"))
    assert len(snapshot["cameras"]) == 341
    pending = [c["camera_id"] for c in snapshot["cameras"] if c["category"] == "AVERAGE_SPEED_ZONE"
               and not (c.get("average_zone_entry_verified") or c.get("average_zone_exit_verified"))]
    assert len(pending) == 12
    assert any(n.startswith("lib/arm64-v8a/") for n in new.namelist())
    assert any(n.startswith("lib/armeabi-v7a/") for n in new.namelist())

reports = Path("app/build/test-results/testDebugUnitTest")
totals = dict(tests=0, failures=0, errors=0, skipped=0)
suites = {}
evidence = []
for path in reports.glob("TEST-*.xml"):
    report = ET.parse(path).getroot()
    count = {key: int(report.get(key, 0)) for key in totals}
    for key in totals:
        totals[key] += count[key]
    if "Camera872" in report.get("name", ""):
        suites[report.get("name")] = count
        evidence.append(report.findtext("system-out", ""))
assert totals["tests"] >= 587, totals
assert not any(totals[k] for k in ("failures", "errors", "skipped")), totals
assert len(suites) == 3
(output / "B872-Official-2KM-replay-logs.txt").write_text(
    "SYNTHETIC GPS + ROBOLECTRIC MEDIA LIFECYCLE; PHYSICAL GPS/SPEAKER/BLUETOOTH NOT TESTED\n\n" + "\n".join(evidence))
result = {
    "official_merge": True,
    "merge_commit": os.environ.get("GITHUB_SHA") or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "approved_testing_commit": APPROVED_TESTING,
    "baseline_commit": BASELINE_COMMIT,
    "baseline_apk_sha256": BASELINE_SHA256,
    "official_apk_sha256": hashlib.sha256(official.read_bytes()).hexdigest(),
    "application_id": "com.izzyan.sgdeliveryplanner",
    "version_name": "1.4.60-b872",
    "version_code": 73,
    "signing_certificate_sha256": ORIGINAL_CERT,
    "original_mp3_unchanged": 209,
    "camera_database_unchanged": 341,
    "camera_icon_dimensions": {"before": [48, 56], "after": [58, 68], "vector_scale": 1.2},
    "all_unit_tests": totals,
    "camera_test_suites": suites,
    "physical_gps_speaker_bluetooth": "NOT TESTED",
    "average_zone_unverified_boundary_records": pending,
    "verified_dual_production_records": sum(bool(c.get("speed_enforcement_evidence_url")) for c in snapshot["cameras"]),
    "official_update_tag": "v1.4.60-b872",
}
(output / "B872-Official-acceptance.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
