#!/usr/bin/env python3
"""Verify actual APK byte integrity against B871 and collect labelled acceptance evidence."""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

from restore_b871_assets872 import BASELINE_SHA256

baseline, testing, output = map(Path, sys.argv[1:4])
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256
output.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(testing) as new:
    assert new.testzip() is None
    voices = [n for n in old.namelist() if n.startswith("assets/voicepacks/") and not n.endswith("/")]
    for name in voices:
        assert new.read(name) == old.read(name), name
    assert len([n for n in voices if n.endswith(".mp3")]) == 209
    for name in ("assets/sg_enforcement_cameras.json", "assets/sg_speed_camera_reference_867.json"):
        assert new.read(name) == old.read(name), name
    snapshot = json.loads(new.read("assets/sg_enforcement_cameras.json"))
    assert len(snapshot["cameras"]) == 341
    pending = [c["camera_id"] for c in snapshot["cameras"] if c["category"] == "AVERAGE_SPEED_ZONE"
               and not (c.get("average_zone_entry_verified") or c.get("average_zone_exit_verified"))]

reports = Path("app/build/test-results/testDebugUnitTest")
counts = {}
evidence = []
for name in ("Camera872ReplayTest", "Camera872PlaybackTest"):
    report = ET.parse(reports / ("TEST-com.izzyan.sgdeliveryplanner." + name + ".xml")).getroot()
    counts[name] = {key: int(report.get(key, 0)) for key in ("tests", "failures", "errors", "skipped")}
    assert counts[name]["tests"] > 0 and not any(counts[name][k] for k in ("failures", "errors", "skipped")), counts
    evidence.append(report.findtext("system-out", ""))
(output / "B872-2KM-replay-logs.txt").write_text(
    "SYNTHETIC GPS + ROBOLECTRIC MEDIA LIFECYCLE; NO PHYSICAL DEVICE OR BLUETOOTH CERTIFICATION\n\n" + "\n".join(evidence))
result = {
    "baseline_commit": "dd368b27ef30fccadbfc62ab69ceed2aeb0c770f",
    "baseline_sha256": BASELINE_SHA256,
    "testing_apk_sha256": hashlib.sha256(testing.read_bytes()).hexdigest(),
    "original_mp3_unchanged": 209,
    "camera_database_unchanged": 341,
    "camera_icon_dimensions": {"before": [48, 56], "after": [58, 68], "vector_scale": 1.2},
    "automated_tests": counts,
    "physical_gps_speaker_bluetooth": "NOT TESTED - runner device acceptance required",
    "average_zone_unverified_boundary_records": pending,
    "verified_dual_production_records": sum(1 for c in snapshot["cameras"] if c.get("speed_enforcement_evidence_url")),
    "official_merge": False,
}
(output / "B872-acceptance.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
