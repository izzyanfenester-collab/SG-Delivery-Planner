#!/usr/bin/env python3
"""B877 bounded silent-delivery change and independent baseline/APK acceptance."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

BASE = "2c2f0cd84b7d950965794ceb4c4738774a3257a9"
BASE_APK_SHA = "c8ac3eb3fdf5bf14c7e3d1d8929d4abde6e3b8e4892c63ff807234dac6bf6bec"
ROOT = pathlib.Path(__file__).resolve().parents[1]
PREFIX = "app/src/main/java/com/izzyan/sgdeliveryplanner/"
OUT = ROOT / "dist"
OUT.mkdir(exist_ok=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def original(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT).decode()

def write_json(name, data):
    (OUT / name).write_text(json.dumps(data, indent=2) + "\n")

def source():
    expected = {}
    path = PREFIX + "PlannerViewModel.kt"
    expected[path] = original(path).replace(
        "                // Announce only after the actual delivery action has been persisted.\n"
        "                deliveryVoiceEvent(planned,updated,action)?.let { DeliveryVoiceAnnouncements.play(getApplication(),it) }\n",
        "                // Delivery completion and report updates are intentionally silent.\n")
    path = PREFIX + "MainActivity.kt"
    expected[path] = original(path).replace(
        "    override fun onResume() { super.onResume(); DeliveryVoiceAnnouncements.resume() }\n"
        "    override fun onPause() { DeliveryVoiceAnnouncements.pause(); super.onPause() }\n"
        "    override fun onDestroy() { DeliveryVoiceAnnouncements.close(); super.onDestroy() }\n", "")
    path = PREFIX + "PandaVoiceMapper.kt"
    expected[path] = original(path).split("internal fun deliveryVoiceEvent(")[0]
    path = PREFIX + "NavigationVoiceAudio.kt"
    expected[path] = original(path).split("/** Delivery events use the same controller,")[0]
    path = "app/build.gradle.kts"
    expected[path] = original(path).replace(
        'versionCode = 84; versionName = "1.4.64-b876"',
        'versionCode = 85; versionName = "1.4.65-b877-testing"'
    ).replace('applicationIdSuffix = ".testing"', 'applicationIdSuffix = ".b877test"')
    path = "app/src/debug/res/values/strings.xml"
    expected[path] = original(path).replace(">SG Delivery<", ">SG Delivery #877 TESTING<")
    for path, content in expected.items():
        actual = (ROOT / path).read_text()
        assert actual == content, "Unapproved source difference: " + path
        assert content != original(path), "Patch failed: " + path
    extras = {
        "app/src/androidTest/java/com/izzyan/sgdeliveryplanner/SilentDelivery877Test.kt",
        ".github/workflows/build-b877-silent-delivery-testing.yml",
        "scripts/run_silent_delivery877.sh",
        "scripts/verify_b877_testing.py",
    }
    changed = set(subprocess.check_output(["git", "diff", "--name-only", BASE, "HEAD"], cwd=ROOT).decode().splitlines())
    assert changed == set(expected) | extras, changed ^ (set(expected) | extras)
    for path in (ROOT / "app/src/main/java").rglob("*.kt"):
        text = path.read_text()
        assert "DeliveryVoiceAnnouncements" not in text, str(path)
        assert "deliveryVoiceEvent" not in text, str(path)
        assert not re.search(r'import\s+android\.speech\.tts\.', text), str(path)
        assert not re.search(r'"(?:next_delivery|delivery_complete)"', text), str(path)
    # NavigationVoiceAudio class and mapper retain every original byte before the
    # removed delivery-only trailer. No volume, player, queue, focus or GPS edits.
    result = {
        "result": "PASS", "baseline": BASE, "changedFiles": sorted(changed),
        "deliveryDispatchRemoved": True, "deliveryEventMappingRemoved": True,
        "deliveryOnlyControllerRemoved": True, "noDeliveryQueueCanBeCreated": True,
        "navigationControllerByteIdentical": True,
        "navigationMapperByteIdentical": True,
        "otherProductionFilesUnchanged": True,
        "allOriginalUnitTestsUnchanged": True,
        "appDataSchemaUnchanged": True, "officialMerge": False,
        "changedFileSha256": {p: sha((ROOT / p).read_bytes()) for p in sorted(changed)},
    }
    write_json("B877-source-audit.json", result)
    print(json.dumps({k:v for k,v in result.items() if k != "changedFileSha256"}))

def checked_base(path):
    assert sha(pathlib.Path(path).read_bytes()) == BASE_APK_SHA, "Wrong B876 Official APK"
    return zipfile.ZipFile(path)

def restore(path):
    with checked_base(path) as baseline:
        names = [n for n in baseline.namelist() if n.startswith("assets/") and not n.endswith("/")]
        assert len(names) == 438, len(names)
        for name in names:
            relative = pathlib.PurePosixPath(name)
            assert ".." not in relative.parts
            target = ROOT / "app/src/main" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(baseline.read(name))
        print("Restored all 438 exact B876 Official assets, including 209 MP3 and 209 PCM.")

def xml_counts(folder):
    count = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list(pathlib.Path(folder).rglob("TEST-*.xml"))
    assert files, folder
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for key in count:
                count[key] += int(suite.attrib.get(key, 0))
    assert count["tests"] > 0 and not any(count[k] for k in ("failures", "errors", "skipped")), count
    return count

def verify(base_path, apk_path):
    source()
    with checked_base(base_path) as baseline, zipfile.ZipFile(apk_path) as tested:
        assets = {n: sha(baseline.read(n)) for n in baseline.namelist()
                  if n.startswith("assets/") and not n.endswith("/")}
        now = {n: sha(tested.read(n)) for n in tested.namelist()
               if n.startswith("assets/") and not n.endswith("/")}
        assert assets == now, "APK asset content differs from Official baseline"
        assert len(assets) == 438
        assert sum(n.endswith(".mp3") for n in assets) == 209
        assert sum(n.endswith(".pcm") for n in assets) == 209
        # Locate retained completion recordings by original manifest IDs.
        manifests = [json.loads(tested.read(n)) for n in assets if n.endswith("/manifest.json") and "voicepacks/" in n]
        retained = [clip for m in manifests for clip in m.get("clips", [])
                    if clip.get("id") in ("next_delivery", "delivery_complete")]
        assert {clip["id"] for clip in retained} == {"next_delivery", "delivery_complete"}
        write_json("B877-unchanged-asset-sha256.json", assets)
    units = xml_counts(ROOT / "app/build/test-results/testDebugUnitTest")
    native = xml_counts(ROOT / "app/build/outputs/androidTest-results/connected")
    assert units["tests"] >= 663, units
    assert native["tests"] == 4, native
    evidence = OUT / "native/b877-evidence"
    flows = json.loads((evidence / "delivery-flows.json").read_text())
    assert flows["result"] == "PASS" and len(flows["flows"]) == 9
    assert all(f["result"] == "PASS" and f["pandaPlaybackRequested"] is False for f in flows["flows"])
    assert flows["persistedDelivered"] == 3 and flows["gpsVoiceEnabled"] is True and flows["voiceVolume"] == 1
    playback = []
    for clip in ("turn_right", "speed_camera_ahead", "red_light_camera_ahead"):
        event = json.loads((evidence / ("audio-" + clip + ".json")).read_text())
        assert event["result"] == "PASS" and event["offline"] and event["started"] and event["completed"], event
        assert [e["state"] for e in event["events"]] == ["STARTED", "COMPLETED"], event
        playback.append(event)
    acceptance = {
        "result": "PASS", "build": "877 TESTING", "baseline": "876 Official",
        "baselineCommit": BASE, "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"]).decode().strip(),
        "baselineApkSha256": BASE_APK_SHA, "apkSha256": sha(pathlib.Path(apk_path).read_bytes()),
        "isolatedPackage": "com.izzyan.sgdeliveryplanner.b877test",
        "unitTests": units, "androidNativeTests": native,
        "deliveryFlows": flows, "offlinePlayback": playback,
        "assetsByteIdentical": len(assets), "originalMp3": 209, "originalPcm": 209,
        "completionClipsRetainedButUnmapped": [c["id"] for c in retained],
        "erpRecords": 106, "erpMapLocations": 101, "existingCameraAndSpeedAssetsUnchanged": True,
        "physicalPhoneDriveOrBluetoothTested": False, "officialMerge": False,
    }
    write_json("B877-acceptance.json", acceptance)
    report = f"""# SG Delivery Build #877 TESTING
Date: 21 November 2026 (Singapore)
Baseline: verified Build #876 Official, commit {BASE}
Testing source: {acceptance["sourceCommit"]}
Package: com.izzyan.sgdeliveryplanner.b877test
Version: 85 / 1.4.65-b877-testing

The delivery status-save callback dispatched next_delivery after ordinary completion
and delivery_complete after final completion. The delivery-only event mapper,
dispatcher, controller and its MainActivity lifecycle hooks are removed. No
replacement completion speech is created. That controller and its asynchronous
preparation can no longer be instantiated or queued by any delivery action.
NavigationVoiceAudio itself and its maneuver mapper remain byte-identical.
No phone or application mute is used. GPS voice preference stays ON at 100%.

## Verified results
- {units["tests"]} JVM/Robolectric tests passed, zero failures/errors/skips.
- 4 actual Android API 35 emulator instrumentation tests passed offline.
- Nine production-Activity delivery/report phases requested no Panda playback:
  Delivered with automatic advancement, report image generation/save, Next stop,
  review finish, revisit, final delivery, completed report open, Activity foreground
  return and completed History open.
- All three fixture deliveries persisted in Room; report preview/generated image,
  order details, progress and completion timestamps remained correct.
- Real MediaPlayer STARTED and COMPLETED callbacks confirmed for turn_right,
  speed_camera_ahead and red_light_camera_ahead while offline.
- All 438 B876 Official assets byte-identical, including 209 MP3 and 209 PCM.
  Both original completion clips remain stored without delivery event mappings.
- ERP 106 records / 101 map locations, cameras, speed limits, routing, camera zoom,
  premium themes, WhatsApp, payment, backup and data schema sources unchanged.
- Lint, APK compilation, APK signature verification and 16KB alignment checked
  in the build workflow before publication.

WhatsApp intent behavior, reports, proof storage and saved-data compatibility are
covered by unchanged existing automated regression tests. No actual WhatsApp message
was sent. Activity return was tested through Android lifecycle transitions; a
physical GPS drive, runner-phone speaker listening and Bluetooth route test were
not performed. MediaPlayer callbacks prove playback lifecycle on the emulator,
not physical audibility on a runner phone.

TESTING installs separately and does not overwrite Official or its saved data.
Existing Official data is not automatically copied into the separate test app.
Use the existing Backup & Restore if the same route data is needed for testing.

APK SHA-256: {acceptance["apkSha256"]}
NO OFFICIAL MERGE. Official remains Build #876 pending explicit approval.
"""
    (OUT / "SG-Delivery-B877-Testing-Report.md").write_text(report)
    print(json.dumps(acceptance, indent=2))

if __name__ == "__main__":
    if sys.argv[1] == "source": source()
    elif sys.argv[1] == "restore": restore(sys.argv[2])
    elif sys.argv[1] == "verify": verify(sys.argv[2], sys.argv[3])
    else: raise SystemExit("source | restore APK | verify BASE_APK TEST_APK")
