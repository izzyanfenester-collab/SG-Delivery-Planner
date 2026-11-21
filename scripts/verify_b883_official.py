#!/usr/bin/env python3
"""Validate user-approved B883 dual-circle speed display promoted to canonical signed B883 Official."""
import hashlib, json, os, sqlite3, subprocess, sys, tempfile, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

BASE='690adce6bdddac9191f605ec866b202b0a89b005'
APPROVED='90be6eb1618eefbd338b9712ff829ec2a81823a2'
B879_OFFICIAL_SHA='6f84b752f8c5774735a65249d926d8be350fcbc56b2b2c248bf57ce342760ae4'
B883_TESTING_SHA='8ae2aedec834a75217465686f6370ab91239f7227bbfc33a4f2761052a3b8e66'
EVIDENCE_SHA='d7bba23b69ab95f0eb74fe58ed74112cff0e4ccb3e4e7a16c27b6f9da57b4105'
OFFICIAL_CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
WORKBOOK_SHA='1f74a7542b5cb1f774f4c2c6a2379a4040c49a0ddd9f08c3798db73af77f4e26'
EXPECTED_CHANGED={
    'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
    'scripts/verify_b883_official.py','.github/workflows/publish-b883-official.yml',
}

def sha(data):return hashlib.sha256(data).hexdigest()
def commitfile(commit,path):
    return subprocess.check_output(['git','show',commit+':'+path],text=True)
def asset_names(apk):
    return {n for n in apk.namelist() if n.startswith('assets/') and
            not n.endswith('/') and not n.startswith('assets/dexopt/')}

def source():
    for commit in (BASE,APPROVED):
        subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True)
    changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD'],text=True).splitlines())
    assert changed==EXPECTED_CHANGED,('Unapproved changes after B883 TESTING:', sorted(changed^EXPECTED_CHANGED))
    approved=commitfile(APPROVED,'app/build.gradle.kts')
    expected=approved.replace('versionName = "1.4.71-b883-testing"',
                              'versionName = "1.4.71-b883"').replace(
        'applicationIdSuffix = ".b883test"','applicationIdSuffix = ".testing"')
    assert expected!=approved
    assert Path('app/build.gradle.kts').read_text()==expected
    assert Path('app/src/debug/res/values/strings.xml').read_text()==commitfile(
        BASE,'app/src/debug/res/values/strings.xml')
    return {'approved_testing_commit':APPROVED,'original_official_baseline':BASE,
        'only_changed_after_approval':sorted(changed),
        'gps_and_all_other_app_source_byte_identical_to_approved_B883_testing':True,
        'no_database_schema_changes_during_official_promotion':True}

def restore(old_path,testing_path,evidence_path):
    assert sha(Path(old_path).read_bytes())==B879_OFFICIAL_SHA
    assert sha(Path(testing_path).read_bytes())==B883_TESTING_SHA
    assert sha(Path(evidence_path).read_bytes())==EVIDENCE_SHA
    with zipfile.ZipFile(old_path) as old,zipfile.ZipFile(testing_path) as testing:
        names=asset_names(old)
        assert len(names)==436, len(names)
        assert asset_names(testing)==names
        for name in names:
            assert '..' not in Path(name).parts
            assert old.read(name)==testing.read(name),name
            target=Path('app/src/main')/name
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(old.read(name))
    print('Restored all 436 verified identical assets from B879 Official, identical to approved B883 TESTING.')

def verify(old_path,testing_path,evidence_path,official_path,output_dir):
    audit=source()
    assert sha(Path(old_path).read_bytes())==B879_OFFICIAL_SHA
    assert sha(Path(testing_path).read_bytes())==B883_TESTING_SHA
    assert sha(Path(evidence_path).read_bytes())==EVIDENCE_SHA
    out=Path(output_dir);out.mkdir(parents=True,exist_ok=True)
    assert 'Signer #1 certificate SHA-256 digest: '+OFFICIAL_CERT in (out/'signing-verification.txt').read_text()
    with zipfile.ZipFile(old_path) as old,zipfile.ZipFile(testing_path) as testing,zipfile.ZipFile(official_path) as new:
        assert new.testzip() is None
        names=asset_names(old)
        assert len(names)==436 and asset_names(testing)==names and asset_names(new)==names
        for name in names:
            assert old.read(name)==testing.read(name)==new.read(name),name
        assert sha(new.read('assets/erp_source_876.xlsx'))==WORKBOOK_SHA
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        database=new.read('assets/sg_erp_locations_876.db')
        assert sha(database)==manifest['database_sha256']
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'erp.db';path.write_bytes(database)
            with sqlite3.connect(path) as con:
                assert con.execute('PRAGMA quick_check').fetchone()[0]=='ok'
                assert con.execute('SELECT COUNT(*) FROM erp_record').fetchone()[0]==106
                assert con.execute('SELECT COUNT(*) FROM erp_site').fetchone()[0]==101
        assert len([n for n in names if n.endswith('.mp3')])==209
        assert len([n for n in names if n.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
        for abi in ('arm64-v8a','armeabi-v7a'):
            assert any(n.startswith('lib/'+abi+'/') for n in new.namelist())
    with zipfile.ZipFile(evidence_path) as evidence:
        approved_tests=json.loads(evidence.read('B883-Verification.json'))
    assert approved_tests['build']==883 and approved_tests['B883_APK_sha256']==B883_TESTING_SHA
    tests=dict(tests=0,failures=0,errors=0,skipped=0)
    for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        xml=ET.parse(f).getroot()
        for key in tests: tests[key]+=int(xml.get(key,0))
    assert tests['tests']>=711 and tests['tests']==approved_tests['tests']['tests'] and tests['failures']==0 and tests['errors']==0 and tests['skipped']==0,tests
    report={'official_merge':True,'approved_B883_testing_commit':APPROVED,
        'official_commit':os.getenv('GITHUB_SHA'),
        'package_name':'com.izzyan.sgdeliveryplanner',
        'version_code':91,'version_name':'1.4.71-b883',
        'signing_certificate_sha256':OFFICIAL_CERT,
        'B879_official_assets_identical':436,'B883_approved_testing_assets_identical':436,
        'erp_records':106,'erp_locations':101,'Panda_original_MP3_and_PCM_sets':209,
        'camera_database_records':341,'unit_tests':tests,
        'app_logic_identical_to_approved_testing':True,
        'B883_prerelease_evidence_sha256':EVIDENCE_SHA,
        'physical_road_and_bluetooth_tests':'NOT PERFORMED',
        'source_audit':audit,'official_apk_sha256':sha(Path(official_path).read_bytes())}
    (out/'B883-Official-acceptance.json').write_text(json.dumps(report,indent=2)+'\n')
    summary=f"""# SG Delivery Build #883 Official

User-approved promotion of #883 TESTING ({APPROVED}).
Official source: {os.getenv('GITHUB_SHA')}
Package: com.izzyan.sgdeliveryplanner
Version: 91 / 1.4.71-b883

Both bottom-panel speed gauges retain the approved 52dp circles, aligned horizontally,
with number-only displays. Overspeed red text, ERP icons, Panda Warrior audio, camera
alerts, compact GPS controls, destination Block/Unit/Postal and all existing functions
retain the exact approved #883 application source.

Validation: {tests['tests']} fresh JVM/Robolectric tests passed with zero failures,
errors or skips. Lint, release build, signature and 16KB ZIP alignment passed.
All 436 application assets match #879 Official and #883 TESTING byte for byte,
including 209 MP3/PCM sets, 341 cameras and ERP 106 records / 101 locations.
Original Official package and signing certificate retained; versionCode increases
from 87 to 91. Data schema and persistence code are unchanged during promotion.
The Official APK updates the existing Official app without uninstalling it.

No new physical device upgrade, road drive, screen alignment or Bluetooth listening
session was performed. These are CI and source/asset-preservation checks.

APK SHA-256: {report['official_apk_sha256']}
Signing certificate SHA-256: {OFFICIAL_CERT}
Published as latest stable Official for the existing in-app update checker.
"""
    (out/'SG-Delivery-B883-Official-Report.md').write_text(summary)
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
    elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:7])
    else:raise SystemExit('Usage: source | restore B879 B883 evidence | verify B879 B883 evidence official output')
