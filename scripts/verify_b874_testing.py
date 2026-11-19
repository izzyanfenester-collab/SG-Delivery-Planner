#!/usr/bin/env python3
"""Audit a background-only GPS control change against exact Official B873."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

BASELINE = "91bb7dbe5295724640ed0ce6dfab9ea93a7e82ee"
BASELINE_SHA256 = "deb7790f43e1937d4a7df50b9d6f04304942ceef5d4dfac9afd507d3048aad82"
MAIN = "app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt"
OLD = "                containerColor = if (active) PremiumGold else PremiumNavy.copy(alpha = 0.96f),"
NEW = "                // Reuse the speedometer/speed-limit host alpha on the surface only.\n" \
      "                containerColor = if (active) PremiumGold.copy(alpha = SGColors.Card.alpha) else SGColors.Card,"

def source_audit():
    subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], check=True)
    old = subprocess.check_output(["git", "show", BASELINE + ":" + MAIN], text=True)
    new = Path(MAIN).read_text()
    assert old.count(OLD) == 1
    assert new == old.replace(OLD, NEW), "Unexpected application change beyond control backgrounds"
    changed = set(subprocess.check_output(["git", "diff", "--name-only", BASELINE, "HEAD", "--",
                                         "app/src/main", "app/src/test"], text=True).splitlines())
    assert changed == {MAIN}, changed
    build = subprocess.check_output(["git", "show", BASELINE + ":app/build.gradle.kts"], text=True)
    assert Path("app/build.gradle.kts").read_text() == build.replace(
        'versionCode = 79; versionName = "1.4.61-b873"',
        'versionCode = 80; versionName = "1.4.62-b874-testing"').replace(
        'applicationIdSuffix = ".testing"', 'applicationIdSuffix = ".b874test"')
    label = "app/src/debug/res/values/strings.xml"
    expected = subprocess.check_output(["git", "show", BASELINE + ":" + label], text=True)
    assert Path(label).read_text() == expected.replace(
        '>SG Delivery<', '>SG Delivery #874 TESTING<')
    assert 'const val SG_CARD_BACKGROUND_ALPHA = 0.30f' in Path(
        "app/src/main/java/com/izzyan/sgdeliveryplanner/SGDesignSystem.kt").read_text()
    return {
        "baseline_commit": BASELINE,
        "current_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "only_runtime_change": "GpsControl containerColor; all other application/test source unchanged",
        "controls": ["Follow", "Route", "Zoom +", "Zoom −", "Traffic", "Settings"],
        "background_alpha": 0.30,
        "reference": "Existing SGColors.Card shared by speedometer and speed-limit host",
        "inactive_background": "SGColors.Card",
        "active_background": "PremiumGold.copy(alpha = SGColors.Card.alpha)",
        "content_colors_unchanged": "Active PremiumNavy; inactive White; full opacity",
        "button_size_dp": [65, 61], "icon_size_dp": 25,
        "corner_radius_dp": 12, "spacing_dp": 7,
        "click_handlers_unchanged": True, "positions_unchanged": True,
        "speedometer_and_speed_limit_unchanged": True,
        "top_and_bottom_panels_unchanged": True,
        "gps_voice_camera_and_data_code_unchanged": True,
        "no_new_blur_layers_or_dependencies": True,
        "device_gpu_profiling": "NOT PERFORMED",
    }

def app_assets(apk):
    # DEX baseline profiles are compiler/build-variant output, not saved application data.
    return [n for n in apk.namelist() if n.startswith("assets/")
            and not n.startswith("assets/dexopt/") and not n.endswith("/")]

def require_baseline(path):
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == BASELINE_SHA256

def restore(path):
    require_baseline(path)
    with zipfile.ZipFile(path) as apk:
        assets = app_assets(apk)
        assert len([n for n in assets if n.endswith(".mp3")]) == 209
        for name in assets:
            assert ".." not in Path(name).parts
            target = Path("app/src/main") / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(apk.read(name))
        print(f"Restored {len(assets)} exact B873 app assets, including 209 original MP3/PCM and postal seed")

def verify(baseline, testing, output):
    require_baseline(baseline)
    audit = source_audit()
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(testing) as new:
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
    (output / "B874-source-audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    result = {
        "baseline_commit": BASELINE, "baseline_apk_sha256": BASELINE_SHA256,
        "testing_commit": audit["current_commit"],
        "testing_apk_sha256": hashlib.sha256(Path(testing).read_bytes()).hexdigest(),
        "application_id": "com.izzyan.sgdeliveryplanner.b874test",
        "version_name": "1.4.62-b874-testing", "version_code": 80,
        "all_existing_unit_tests": totals, "background_alpha": 0.30,
        "unchanged_app_asset_files": len(assets), "original_mp3_unchanged": 209,
        "camera_database_unchanged": 341, "postal_seed_unchanged": True,
        "data_schema_changed": False, "official_merge": False,
        "new_tests_added": False,
        "acceptance_method": "Exact runtime source comparison, existing regression tests, APK/assets/signature checks",
        "physical_day_night_2d_3d_control_visual_qa": "NOT PERFORMED",
        "physical_gps_and_button_sessions": "NOT PERFORMED",
    }
    (output / "B874-acceptance.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    action = sys.argv[1]
    if action == "source":
        print(json.dumps(source_audit(), indent=2))
    elif action == "restore":
        restore(sys.argv[2])
    elif action == "verify":
        verify(*sys.argv[2:5])
    else:
        raise SystemExit("Expected source, restore or verify")
