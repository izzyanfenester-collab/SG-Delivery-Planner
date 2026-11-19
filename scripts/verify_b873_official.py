#!/usr/bin/env python3
"""Verify approved B873 source, original Official identity and retained B872 assets."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from restore_b872_assets873 import BASELINE_SHA256

APPROVED_TESTING = "9925d2c81bf24fd03479ae9a08642081c4ad8215"
BASELINE_COMMIT = "ccb07e51c6eba272172021b9600f286d2fc24db2"
ORIGINAL_CERT = "b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99"

baseline, official, output = map(Path, sys.argv[1:4])
assert hashlib.sha256(baseline.read_bytes()).hexdigest() == BASELINE_SHA256
output.mkdir(parents=True, exist_ok=True)
signing = (output / "signing-verification.txt").read_text()
assert "Signer #1 certificate SHA-256 digest: " + ORIGINAL_CERT in signing
subprocess.run(["git", "diff", "--exit-code", APPROVED_TESTING, "HEAD", "--",
                "app/src/main", "app/src/test"], check=True)
path = "app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt"
old = subprocess.check_output(["git", "show", BASELINE_COMMIT + ":" + path], text=True)
new = Path(path).read_text()
marker = "internal class NavigationSession("
assert old[old.index(marker):] == new[new.index(marker):]

with zipfile.ZipFile(baseline) as old_apk, zipfile.ZipFile(official) as new_apk:
    assert new_apk.testzip() is None
    voice = [n for n in old_apk.namelist() if n.startswith("assets/voicepacks/") and not n.endswith("/")]
    for name in voice:
        assert new_apk.read(name) == old_apk.read(name), name
    assert len([n for n in voice if n.endswith(".mp3")]) == 209
    for name in ("assets/sg_enforcement_cameras.json", "assets/sg_speed_camera_reference_867.json",
                 "assets/sg_speed_limits_reference_868.csv"):
        assert new_apk.read(name) == old_apk.read(name), name
    snapshot = json.loads(new_apk.read("assets/sg_enforcement_cameras.json"))
    assert len(snapshot["cameras"]) == 341
    assert any(n.startswith("lib/arm64-v8a/") for n in new_apk.namelist())
    assert any(n.startswith("lib/armeabi-v7a/") for n in new_apk.namelist())

totals = dict(tests=0, failures=0, errors=0, skipped=0)
destination_suites = {}
camera_suites = {}
layout_logs = []
camera_logs = []
for path in Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    root = ET.parse(path).getroot()
    count = {k: int(root.get(k, 0)) for k in totals}
    for k in totals:
        totals[k] += count[k]
    name = root.get("name", "")
    if "NavigationDestination873" in name:
        destination_suites[name] = count
        layout_logs.append(root.findtext("system-out", ""))
    if "Camera872" in name:
        camera_suites[name] = count
        camera_logs.append(root.findtext("system-out", ""))
assert totals["tests"] >= 604, totals
assert not any(totals[k] for k in ("failures", "errors", "skipped")), totals
assert len(destination_suites) == 3, destination_suites
assert len(camera_suites) == 3, camera_suites
image = Path("app/build/reports/b873-ui/SG-Delivery-B873-Bottom-Panel.png")
assert image.is_file() and image.stat().st_size > 1000
(output / "B873-Official-layout-logs.txt").write_text(
    "ACTUAL COMPOSE COMPONENT, ROBOLECTRIC NATIVE GRAPHICS; FIXTURE DATA; NOT A PHYSICAL PHONE\n\n"
    + "\n".join(layout_logs))
(output / "B873-Official-camera-regression-logs.txt").write_text(
    "SYNTHETIC GPS REPLAY + ROBOLECTRIC PLAYBACK; PHYSICAL GPS/SPEAKER/BLUETOOTH NOT TESTED\n\n"
    + "\n".join(camera_logs))
result = {
    "official_merge": True,
    "merge_commit": os.environ.get("GITHUB_SHA") or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    "approved_testing_commit": APPROVED_TESTING,
    "baseline_commit": BASELINE_COMMIT,
    "baseline_apk_sha256": BASELINE_SHA256,
    "official_apk_sha256": hashlib.sha256(official.read_bytes()).hexdigest(),
    "application_id": "com.izzyan.sgdeliveryplanner",
    "version_name": "1.4.61-b873",
    "version_code": 79,
    "previous_official_version_code": 73,
    "signing_certificate_sha256": ORIGINAL_CERT,
    "upgrade_identity_matches_b872": True,
    "original_mp3_unchanged": 209,
    "camera_database_unchanged": 341,
    "approved_application_sources_unchanged": True,
    "gps_engine_unchanged_from_b872": True,
    "data_schema_changed": False,
    "all_unit_tests": totals,
    "destination_test_suites": destination_suites,
    "camera_test_suites": camera_suites,
    "fonts": "26sp ExtraBold block/unit/postal; 29sp ETA; font scaling preserved",
    "ui_evidence": "Actual Compose component using native Canvas in simulated Android; fixture addresses",
    "physical_runner_device": "NOT TESTED",
    "official_update_tag": "v1.4.61-b873",
}
(output / "B873-Official-acceptance.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
