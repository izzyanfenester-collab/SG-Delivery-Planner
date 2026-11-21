#!/usr/bin/env python3
"""Verify B879 red-font and Panda-voice FIX against approved B878 TESTING."""
import hashlib,json,os,sqlite3,subprocess,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
BASE='2642528196f5dc36c8bbdfa1dde088837747977b'
OFFICIAL_SHA='36c6da592288f97bde6453ec2d6aa21f5c5c46523f8b2ac872e8d06b35458520'
ALLOWED={
 'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/OverspeedVisual879.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/OverspeedSafety878.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/CameraSafetySettingsUi.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/OverspeedVisual879Test.kt',
 'scripts/verify_b879_testing.py','.github/workflows/build-b879-testing.yml'
}
def sha(raw):return hashlib.sha256(raw).hexdigest()
def app_assets(apk):return {n for n in apk.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def audit(restored=False):
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
    changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],text=True).splitlines())
    assert changed==ALLOWED,('Source files changed beyond target',sorted(changed^ALLOWED))
    before=subprocess.check_output(['git','show',BASE+':app/build.gradle.kts'],text=True)
    expected=before.replace('versionCode = 86; versionName = "1.4.66-b878-testing"','versionCode = 87; versionName = "1.4.67-b879-testing"').replace('applicationIdSuffix = ".b878test"','applicationIdSuffix = ".b879test"')
    assert expected!=before
    assert Path('app/build.gradle.kts').read_text()==expected
    # Before restore the tracked assets must match Git; after restoring official
    # APK assets they may intentionally differ from repository placeholders.
    # The verify stage then checks *every* asset byte against TESTING B878.
    if not restored:
        for p in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main/assets'],text=True).splitlines():
            assert Path(p).read_bytes()==subprocess.check_output(['git','show',BASE+':'+p]),p
    return {'baseline_commit':BASE,'changed_files':sorted(changed),'all_existing_assets_unchanged':True,
        'no_user_database_or_delivery_code_changed':True,'official_merge':False}
def restore(apk_path):
    data=Path(apk_path).read_bytes();assert sha(data)==OFFICIAL_SHA
    with zipfile.ZipFile(apk_path) as apk:
        original=app_assets(apk)
        assert len(original)==436,len(original)
        for name in original:
            assert '..' not in Path(name).parts
            path=Path('app/src/main')/name;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(apk.read(name))
    print('Restored 436 byte-identical B878 TESTING assets, including ERP, 209 Panda MP3+PCM and all road data')
def totals():
    count=dict(tests=0,errors=0,failures=0,skipped=0)
    for p in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        t=ET.parse(p).getroot()
        for key in count:count[key]+=int(t.get(key,0))
    return count
def verify(official_path,testing_path,output_path):
    info=audit(restored=True);assert sha(Path(official_path).read_bytes())==OFFICIAL_SHA
    output=Path(output_path);output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(official_path) as old,zipfile.ZipFile(testing_path) as new:
        assert new.testzip() is None
        old_assets=app_assets(old);new_assets=app_assets(new)
        assert len(old_assets)==436 and old_assets==new_assets,(len(old_assets),len(new_assets))
        for name in old_assets: assert old.read(name)==new.read(name),name
        db=new.read('assets/sg_erp_locations_876.db')
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        assert sha(db)==manifest['database_sha256']
        assert manifest['record_count']==106 and manifest['location_count']==101
        assert len([x for x in old_assets if x.endswith('.mp3')])==209
        assert len([x for x in old_assets if x.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
    tests=totals()
    assert tests['tests']>=681 and not any(tests[k] for k in ('errors','failures','skipped')),tests
    result={'baseline_commit':BASE,'official_apk_sha256':OFFICIAL_SHA,
        'testing_apk_sha256':sha(Path(testing_path).read_bytes()),
        'application_id':'com.izzyan.sgdeliveryplanner.b879test',
        'version_name':'1.4.67-b879-testing','version_code':87,
        'original_official_assets_unchanged':436,'erp_records':106,'erp_map_sites':101,
        'Panda_MP3_and_PCM_unchanged':209,'camera_records_unchanged':341,
        'tests':tests,'native_phone_drive_or_bluetooth_test':'NOT PERFORMED',
        'notes':'150/90 map-data red font, independent voice confidence, GPS accuracy fallback regression tested; physical phone/Bluetooth still requires runner QA',
        'source_audit':info}
    (output/'B879-Verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Expected source, restore <apk>, or verify <official> <testing> <output>')
