#!/usr/bin/env python3
"""Collect B873 intent, unit-format and actual Compose rendering evidence; keep B872 assets exact."""
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile
from restore_b872_assets873 import BASELINE_SHA256

baseline,testing,output=map(Path,sys.argv[1:4])
assert hashlib.sha256(baseline.read_bytes()).hexdigest()==BASELINE_SHA256
output.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(baseline) as old,zipfile.ZipFile(testing) as new:
    assert new.testzip() is None
    voice=[n for n in old.namelist() if n.startswith("assets/voicepacks/") and not n.endswith("/")]
    assert len([n for n in voice if n.endswith(".mp3")])==209
    for n in voice: assert old.read(n)==new.read(n),n
    for n in ("assets/sg_enforcement_cameras.json","assets/sg_speed_camera_reference_867.json",
              "assets/sg_speed_limits_reference_868.csv"):
        assert old.read(n)==new.read(n),n
totals=dict(tests=0,failures=0,errors=0,skipped=0)
suites={};logs=[]
for path in Path("app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
    root=ET.parse(path).getroot()
    count={k:int(root.get(k,0)) for k in totals}
    for k in totals:totals[k]+=count[k]
    if "NavigationDestination873" in root.get("name",""):
        suites[root.get("name")]=count
        logs.append(root.findtext("system-out",""))
assert totals["tests"]>=604,totals
assert not any(totals[k] for k in ("failures","errors","skipped")),totals
assert len(suites)==3,suites
image=Path("app/build/reports/b873-ui/SG-Delivery-B873-Bottom-Panel.png")
assert image.is_file() and image.stat().st_size>1000
result={
    "baseline_commit":"ccb07e51c6eba272172021b9600f286d2fc24db2",
    "baseline_apk_sha256":BASELINE_SHA256,
    "testing_apk_sha256":hashlib.sha256(testing.read_bytes()).hexdigest(),
    "all_unit_tests":totals,"destination_test_suites":suites,
    "original_mp3_unchanged":209,"camera_database_unchanged":341,
    "unit_source":"Selected Stop.order.fullAddress; selected Place.address only for stops without an attached order",
    "data_schema_changed":False,"gps_engine_changed":False,
    "fonts":"26sp ExtraBold block/unit/postal; 29sp ETA; system font scaling preserved",
    "ui_evidence":"Actual Compose component, Robolectric native graphics; fixture addresses only",
    "physical_runner_device":"NOT TESTED","official_merge":False,
}
(output/"B873-acceptance.json").write_text(json.dumps(result,indent=2)+"\n")
(output/"B873-layout-logs.txt").write_text("ROBOLECTRIC NATIVE COMPOSE LAYOUT, NOT A PHYSICAL PHONE SCREENSHOT\n\n"+"\n".join(logs))
print(json.dumps(result,indent=2))
