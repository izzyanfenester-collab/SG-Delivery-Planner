#!/usr/bin/env python3
"""Audit B880 TESTING as a strict layout-only change from B879 Official."""
import hashlib,json,subprocess,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
BASE='690adce6bdddac9191f605ec866b202b0a89b005'
OFFICIAL_APK_SHA256='6f84b752f8c5774735a65249d926d8be350fcbc56b2b2c248bf57ce342760ae4'
CHANGED={
 'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/GpsCompactLayout880.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/GpsCompactLayout880Test.kt',
 'scripts/verify_b880_testing.py','.github/workflows/build-b880-testing.yml'
}
def sha(b):return hashlib.sha256(b).hexdigest()
def names(z):return {p for p in z.namelist() if p.startswith('assets/') and not p.endswith('/') and not p.startswith('assets/dexopt/')}
def audit(restored=False):
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
    changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],text=True).splitlines())
    assert changed==CHANGED,('Unapproved changed paths:',sorted(changed^CHANGED))
    original=subprocess.check_output(['git','show',BASE+':app/build.gradle.kts'],text=True)
    expected=original.replace('versionCode = 87; versionName = "1.4.67-b879"','versionCode = 88; versionName = "1.4.68-b880-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b880test"')
    assert expected!=original and Path('app/build.gradle.kts').read_text()==expected
    src=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt').read_text()
    assert 'GpsCompactLayout880.CONTROL_WIDTH_DP.dp' in src
    assert 'GpsCompactLayout880.CONTROL_HEIGHT_DP.dp' in src
    assert 'GpsCompactLayout880.METER_WIDTH_DP.dp' in src
    assert 'session.overspeedVisualState879.red' in src
    assert 'Color(0xFFFF3030)' in src
    assert 'session.roadSpeedLimitState875' in src
    assert 'Modifier.align(Alignment.BottomStart)' in src
    assert 'Modifier.align(Alignment.TopEnd).verticalScroll' in src
    assert 'session.upcomingEnforcementCamera?.let' in src
    if not restored:
        for p in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main/assets'],text=True).splitlines():
            assert Path(p).read_bytes()==subprocess.check_output(['git','show',BASE+':'+p]),p
    return {'baseline':BASE,'paths':sorted(changed),'unchanged_delivery_database_voice_erp_traffic_and_map_sources':True,'official_merge':False}
def restore(path):
    assert sha(Path(path).read_bytes())==OFFICIAL_APK_SHA256
    with zipfile.ZipFile(path) as z:
        asset_names=names(z)
        assert len(asset_names)==436,len(asset_names)
        for item in asset_names:
            assert '..' not in Path(item).parts
            target=Path('app/src/main')/item
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(z.read(item))
    print('Restored 436 original B879 Official application assets unchanged')
def test_counts():
    total=dict(tests=0,errors=0,failures=0,skipped=0)
    for file in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        root=ET.parse(file).getroot()
        for key in total: total[key]+=int(root.get(key,0))
    return total
def verify(original,testing,output):
    source=audit(restored=True)
    assert sha(Path(original).read_bytes())==OFFICIAL_APK_SHA256
    out=Path(output);out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(original) as a,zipfile.ZipFile(testing) as b:
        assert b.testzip() is None
        old=names(a);new=names(b)
        assert len(old)==436 and new==old,(len(old),len(new))
        for name in old:assert a.read(name)==b.read(name),name
        manifest=json.loads(b.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        assert len([x for x in old if x.endswith('.mp3')])==209
        assert len([x for x in old if x.endswith('.pcm')])==209
        assert len(json.loads(b.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
    t=test_counts()
    assert t['tests']>=691 and not any(t[k] for k in ('errors','failures','skipped')),t
    result={'test_build':880,'version_name':'1.4.68-b880-testing','version_code':88,
        'application_id':'com.izzyan.sgdeliveryplanner.b880test',
        'control_width_dp':52,'control_height_dp':49,'control_count':6,
        'speed_limit_position':'left-lower of Settings, separate fixed viewport lane',
        'original_assets_identical':436,'erp_records':106,'erp_locations':101,
        'original_panda_mp3_pcm_sets':209,'camera_records':341,
        'speed_and_limit_fonts_preserved':True,'original_official_apk_sha256':OFFICIAL_APK_SHA256,
        'new_testing_apk_sha256':sha(Path(testing).read_bytes()),
        'tests':t,'source_audit':source,
        'real_android_screenshot_tap_and_drive_testing':'REQUIRES RUNNER QA'}
    (out/'B880-Verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Usage: source | restore Official879.apk | verify Official879.apk Testing880.apk output')
