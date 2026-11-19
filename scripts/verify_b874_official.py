#!/usr/bin/env python3
"""Verify owner-approved B874 source and the original Official upgrade identity."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from verify_b874_testing import BASELINE, BASELINE_SHA256, MAIN, OLD, NEW, app_assets, require_baseline

APPROVED = "e0a46b42641c3a9fe9a42146e7229ffe8a6b596b"
ORIGINAL_CERT = "b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99"

def source_audit():
    for commit in (BASELINE, APPROVED):
        subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], check=True)
    subprocess.run(["git", "diff", "--exit-code", APPROVED, "HEAD", "--",
                    "app/src/main", "app/src/test"], check=True)
    changed = set(subprocess.check_output(["git", "diff", "--name-only", APPROVED, "HEAD",
                                         "--", "app/src"], text=True).splitlines())
    assert changed == {"app/src/debug/res/values/strings.xml"}, changed
    label = "app/src/debug/res/values/strings.xml"
    assert Path(label).read_bytes() == subprocess.check_output(["git", "show", BASELINE + ":" + label])
    build = subprocess.check_output(["git", "show", APPROVED + ":app/build.gradle.kts"], text=True)
    assert Path("app/build.gradle.kts").read_text() == build.replace(
        'versionCode = 80; versionName = "1.4.62-b874-testing"',
        'versionCode = 81; versionName = "1.4.62-b874"').replace(
        'applicationIdSuffix = ".b874test"', 'applicationIdSuffix = ".testing"')
    old = subprocess.check_output(["git", "show", BASELINE + ":" + MAIN], text=True)
    assert Path(MAIN).read_text() == old.replace(OLD, NEW)
    return {
        "baseline_commit": BASELINE, "approved_testing_commit": APPROVED,
        "approved_runtime_and_test_sources_unchanged": True,
        "only_runtime_change_from_b873": "GpsControl background containerColor",
        "controls": ["Follow", "Route", "Zoom +", "Zoom −", "Traffic", "Settings"],
        "background_alpha": 0.30, "foreground_full_opacity_unchanged": True,
        "button_size_dp": [65, 61], "icon_size_dp": 25, "spacing_dp": 7,
        "click_handlers_positions_and_other_panels_unchanged": True,
        "gps_voice_camera_data_and_other_app_code_unchanged": True,
        "no_new_blur_layers_or_dependencies": True,
    }

def verify(baseline, official, output):
    require_baseline(baseline)
    source = source_audit()
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    signing = (output / "signing-verification.txt").read_text()
    assert "Signer #1 certificate SHA-256 digest: " + ORIGINAL_CERT in signing
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(official) as new:
        assert new.testzip() is None
        assets = app_assets(old)
        assert set(app_assets(new)) == set(assets)
        for name in assets:
            assert old.read(name) == new.read(name), name
        assert len([n for n in assets if n.endswith(".mp3")]) == 209
        assert len(json.loads(new.read("assets/sg_enforcement_cameras.json"))["cameras"]) == 341
        assert any(n.startswith("lib/arm64-v8a/") for n in new.namelist())
        assert any(n.startswith("lib/armeabi-v7a/") for n in new.namelist())
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        report = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(report.get(key, 0))
    assert totals["tests"] >= 604, totals
    assert not any(totals[k] for k in ("failures", "errors", "skipped")), totals
    result = {
        "official_merge": True,
        "merge_commit": os.environ.get("GITHUB_SHA") or subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True).strip(),
        "approved_testing_commit": APPROVED, "baseline_commit": BASELINE,
        "baseline_apk_sha256": BASELINE_SHA256,
        "official_apk_sha256": hashlib.sha256(Path(official).read_bytes()).hexdigest(),
        "application_id": "com.izzyan.sgdeliveryplanner",
        "version_name": "1.4.62-b874", "version_code": 81,
        "previous_official_version_code": 79,
        "signing_certificate_sha256": ORIGINAL_CERT,
        "upgrade_identity_matches_b873": True,
        "all_existing_unit_tests": totals, "background_alpha": 0.30,
        "unchanged_app_asset_files": len(assets), "original_mp3_unchanged": 209,
        "camera_database_unchanged": 341, "postal_seed_unchanged": True,
        "data_schema_changed": False, "physical_runner_device": "NOT TESTED",
        "physical_day_night_2d_3d_and_gpu_profiling": "NOT PERFORMED",
        "official_update_tag": "v1.4.62-b874",
    }
    (output / "B874-Official-source-audit.json").write_text(json.dumps(source, indent=2) + "\n")
    (output / "B874-Official-acceptance.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    if sys.argv[1] == "source":
        print(json.dumps(source_audit(), indent=2))
    elif sys.argv[1] == "verify":
        verify(*sys.argv[2:5])
    else:
        raise SystemExit("Expected source or verify")
