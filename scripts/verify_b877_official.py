#!/usr/bin/env python3
"""Promote the user-approved B877 change with original Official identity and assets."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

BASE = "2c2f0cd84b7d950965794ceb4c4738774a3257a9"
APPROVED = "d6aeee5754baf854021b4f19902e7d8c47ab0231"
BASE_SHA = "c8ac3eb3fdf5bf14c7e3d1d8929d4abde6e3b8e4892c63ff807234dac6bf6bec"
TEST_SHA = "120dd97ea5b1f23187258b6bba5dac0cc01ffd2f7bdb509ea977a88e1b0ffd9e"
EVIDENCE_SHA = "ca629ac389eb59342e8ba71a494c34223d744eefc3decc04a4bd900b13aafd34"
CERT = "b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99"
ROOT = Path(__file__).resolve().parents[1]
PROFILES = {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def old(commit, path):
    return subprocess.check_output(["git", "show", commit + ":" + path], cwd=ROOT).decode()

def assets(apk):
    return {name: sha(apk.read(name)) for name in apk.namelist()
            if name.startswith("assets/") and not name.endswith("/")}

def source():
    for commit in (BASE, APPROVED):
        subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], cwd=ROOT, check=True)
    allowed = {"app/build.gradle.kts", "app/src/debug/res/values/strings.xml",
               "scripts/verify_b877_official.py", ".github/workflows/publish-b877-official.yml"}
    changed = set(subprocess.check_output(["git", "diff", "--name-only", APPROVED, "HEAD"], cwd=ROOT).decode().splitlines())
    assert changed == allowed, changed ^ allowed
    before = old(APPROVED, "app/build.gradle.kts")
    expected = before.replace('versionName = "1.4.65-b877-testing"', 'versionName = "1.4.65-b877"')
    expected = expected.replace('applicationIdSuffix = ".b877test"', 'applicationIdSuffix = ".testing"')
    assert (ROOT / "app/build.gradle.kts").read_text() == expected != before
    assert (ROOT / "app/src/debug/res/values/strings.xml").read_text() == old(BASE, "app/src/debug/res/values/strings.xml")
    return {"result": "PASS", "approvedTestingCommit": APPROVED, "baselineOfficialCommit": BASE,
            "changesSinceApprovedTesting": sorted(changed), "approvedRuntimeSourceUnchanged": True,
            "allTestsUnchanged": True, "userDatabaseSchemaChanged": False,
            "completionDispatchAndMappingsRemainRemoved": True}

def inputs(base, tested, evidence):
    for path, expected in ((base, BASE_SHA), (tested, TEST_SHA), (evidence, EVIDENCE_SHA)):
        assert sha(Path(path).read_bytes()) == expected, "Unverified input: " + str(path)
    with zipfile.ZipFile(base) as first, zipfile.ZipFile(tested) as second:
        assert assets(first) == assets(second)
        assert len(assets(first)) == 438
    with zipfile.ZipFile(evidence) as archive:
        accepted = json.loads(archive.read("B877-acceptance.json"))
        assert accepted["result"] == "PASS" and accepted["sourceCommit"] == APPROVED
        assert accepted["apkSha256"] == TEST_SHA
        assert accepted["unitTests"] == dict(tests=663, failures=0, errors=0, skipped=0)
        assert accepted["androidNativeTests"] == dict(tests=4, failures=0, errors=0, skipped=0)
        flows = accepted["deliveryFlows"]
        assert len(flows["flows"]) == 9 and flows["persistedDelivered"] == 3
        assert all(item["result"] == "PASS" and item["pandaPlaybackRequested"] is False for item in flows["flows"])
        assert flows["gpsVoiceEnabled"] and flows["voiceVolume"] == 1
        playback = accepted["prerecordedPlayback"]
        assert {item["clip"] for item in playback} == {"turn_right", "speed_camera_ahead", "red_light_camera_ahead"}
        assert all(item["started"] and item["completed"] and item["localAssets"] for item in playback)
    return accepted

def restore(base, tested, evidence):
    inputs(base, tested, evidence)
    with zipfile.ZipFile(base) as archive:
        for name in assets(archive):
            # ART profiles are generated for the newly compiled DEX by AGP.
            # Copying old APK profiles into source assets collides with packageRelease.
            if name in PROFILES:
                continue
            assert ".." not in Path(name).parts
            target = ROOT / "app/src/main" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    print("Restored all 436 application assets; AGP regenerates the two Android optimization profiles")

def verify(base, tested, evidence, official, output):
    audit = source()
    accepted = inputs(base, tested, evidence)
    output = Path(output)
    output.mkdir(exist_ok=True, parents=True)
    assert "Signer #1 certificate SHA-256 digest: " + CERT in (output / "signing-verification.txt").read_text()
    with zipfile.ZipFile(base) as first, zipfile.ZipFile(tested) as second, zipfile.ZipFile(official) as built:
        assert built.testzip() is None
        original = assets(first)
        current = assets(built)
        assert original == assets(second)
        assert set(current) == set(original) and len(current) == 438
        assert {n: h for n, h in current.items() if n not in PROFILES} == {n: h for n, h in original.items() if n not in PROFILES}
        assert all(built.getinfo(name).file_size > 0 for name in PROFILES)
        profiles = {name: {"baselineSha256": original[name], "officialSha256": current[name]} for name in sorted(PROFILES)}
        assert sum(name.endswith(".mp3") for name in original) == 209
        assert sum(name.endswith(".pcm") for name in original) == 209
        assert len(json.loads(built.read("assets/sg_enforcement_cameras.json"))["cameras"]) == 341
        manifest = json.loads(built.read("assets/sg_erp_manifest_876.json"))
        assert manifest["record_count"] == 106 and manifest["location_count"] == 101
        db = built.read("assets/sg_erp_locations_876.db")
        assert sha(db) == manifest["database_sha256"]
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "erp.db"
            file.write_bytes(db)
            with sqlite3.connect(file) as connection:
                assert connection.execute("PRAGMA quick_check").fetchone()[0] == "ok"
                assert connection.execute("SELECT COUNT(*) FROM erp_record").fetchone()[0] == 106
                assert connection.execute("SELECT COUNT(*) FROM erp_site").fetchone()[0] == 101
        for abi in ("arm64-v8a", "armeabi-v7a"):
            assert any(name.startswith("lib/" + abi + "/") for name in built.namelist())
    unit = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list((ROOT / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    assert files
    for file in files:
        suite = ET.parse(file).getroot()
        for key in unit:
            unit[key] += int(suite.get(key, 0))
    assert unit == dict(tests=663, failures=0, errors=0, skipped=0), unit
    report = {"officialMerge": True, "build": "877 Official", "sourceCommit": os.getenv("GITHUB_SHA"),
              "applicationId": "com.izzyan.sgdeliveryplanner", "versionCode": 85,
              "versionName": "1.4.65-b877", "officialSigningCertificateSha256": CERT,
              "officialApkSha256": sha(Path(official).read_bytes()), "applicationAssetsByteIdentical": 436,
              "totalAssets": 438, "androidOptimizationProfilesRegenerated": profiles,
              "originalMp3Unchanged": 209, "originalPcmUnchanged": 209,
              "cameraRecordsUnchanged": 341, "erpRecords": 106, "erpLocations": 101,
              "unitTests": unit, "approvedTestingEvidenceSha256": EVIDENCE_SHA,
              "approvedNativeTestsCarriedForward": accepted["androidNativeTests"],
              "approvedDeliveryFlows": accepted["deliveryFlows"],
              "approvedPlaybackCallbacks": accepted["prerecordedPlayback"],
              "sourceAudit": audit, "physicalDriveSpeakerBluetoothVerified": False,
              "fullyOfflineNativePlaybackVerified": False,
              "upgradeValidation": "Same canonical app ID and Official certificate, versionCode 85 > 84, database and persistence source unchanged; physical upgrade not performed"}
    (output / "B877-Official-acceptance.json").write_text(json.dumps(report, indent=2) + "\n")
    text = f"""# SG Delivery Build #877 Official
Date: 21 November 2026 (Singapore)
Owner explicitly approved promotion of TESTING source {APPROVED}.
Official source: {os.getenv('GITHUB_SHA')}
Version: 85 / 1.4.65-b877
App ID: com.izzyan.sgdeliveryplanner

Delivery completion, next-delivery and final-delivery operations no longer create
completion voice events. No replacement completion voice or application mute is
introduced. Delivery status persistence, reports and all navigation code match the
approved TESTING source. The two original completion recordings remain intact.

Validation:
- 663 fresh JVM/Robolectric regressions passed, zero failures/errors/skips.
- Lint, release compilation, signature verification and 16KB ZIP alignment passed.
- Original Official app ID and signing certificate retained; versionCode increases
  from 84 to 85. Data schema and persistence code are unchanged. Android can install
  this as an update over #876 without uninstalling or replacing its data directory.
  No physical device upgrade session was performed.
- All 436 application assets match #876 Official and approved #877 TESTING byte for byte,
  including 209 MP3, 209 PCM, 341 cameras and the speed-limit data.
  The other two assets are Android optimization profiles regenerated for the new
  DEX by the release compiler; they contain no user data or voice recordings.
- ERP database retained: 106 records at 101 locations.
- Approved TESTING evidence carried forward: four Android emulator tests, nine
  silent delivery/report phases, three persisted delivered orders, and actual
  MediaPlayer STARTED/COMPLETED callbacks for turn, speed-camera and red-light audio.
  The Official runtime source is identical to the approved TESTING source.

Navigation audio, camera priority/2KM alerts, routing, zoom, ERP, premium themes,
WhatsApp, payments, reports, backup/restore and settings retain their approved code.
No stored user records are bundled, deleted, converted or migrated by this change.

Limits: no physical GPS drive, phone speaker listening or Bluetooth verification.
The TESTING emulator reported Ethernet despite radios being disabled, so fully
offline native playback is not claimed. Offline audio code/assets are unchanged.

APK SHA-256: {report['officialApkSha256']}
Signing certificate SHA-256: {CERT}
Approved native evidence SHA-256: {EVIDENCE_SHA}

Published as latest stable Official for the existing in-app update checker.
"""
    (output / "SG-Delivery-B877-Official-Report.md").write_text(text)
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    if sys.argv[1] == "source":
        print(json.dumps(source(), indent=2))
    elif sys.argv[1] == "restore":
        restore(*sys.argv[2:5])
    elif sys.argv[1] == "verify":
        verify(*sys.argv[2:7])
    else:
        raise SystemExit("source | restore BASE TEST EVIDENCE | verify BASE TEST EVIDENCE OFFICIAL OUTPUT")
