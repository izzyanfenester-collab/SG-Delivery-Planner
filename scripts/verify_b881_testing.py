#!/usr/bin/env python3
"""B881 TESTING validation: approved B880 compact controls plus bottom-only horizontal meters."""
import hashlib,json,subprocess,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
BASE='1aa685986982f09ef4a275acc5a17ddca24fdce5'
B879_OFFICIAL_APK_SHA256='6f84b752f8c5774735a65249d926d8be350fcbc56b2b2c248bf57ce342760ae4'
ALLOWED={
 'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationDestination873.kt',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881Test.kt',
 'scripts/verify_b881_testing.py','.github/workflows/build-b881-testing.yml'
}
def sha(b): return hashlib.sha256(b).hexdigest()
def assets(z):return {p for p in z.namelist() if p.startswith('assets/') and not p.endswith('/') and not p.startswith('assets/dexopt/')}
def gitfile(path):return subprocess.check_output(['git','show',BASE+':'+path],text=True)
def audit(restored=False):
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
    changes=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],text=True).splitlines())
    assert changes==ALLOWED, ('Unapproved modifications:',sorted(changes^ALLOWED))
    expected=gitfile('app/build.gradle.kts').replace(
        'versionCode = 88; versionName = "1.4.68-b880-testing"',
        'versionCode = 89; versionName = "1.4.69-b881-testing"').replace(
        'applicationIdSuffix = ".b880test"','applicationIdSuffix = ".b881test"')
    assert expected!=gitfile('app/build.gradle.kts')
    assert Path('app/build.gradle.kts').read_text()==expected
    activity=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/BuiltInNavigationActivity.kt').read_text()
    panel=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationDestination873.kt').read_text()
    gauge=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt').read_text()
    assert 'NavigationBottomSpeedMeters881(' in activity
    assert 'speedMeters={' in activity
    assert 'GpsCompactLayout880.CONTROL_WIDTH_DP.dp' in activity
    assert 'GpsCompactLayout880.CONTROL_HEIGHT_DP.dp' in activity
    assert 'session.overspeedVisualState879.red' in activity
    assert 'Modifier.align(Alignment.BottomStart)' in activity # camera advisory only
    assert 'GpsCompactLayout880.METER_WIDTH_DP.dp' not in activity
    assert 'Surface(color = SGColors.Card,' not in activity # no floating speed card
    assert 'speedMeters:(@Composable ()->Unit)?=null' in panel
    assert 'gps-bottom-speed-actions' in panel and 'gps-bottom-meters-row' in panel
    assert 'NavigationBottomSpeedLayout881.inlineWithActions' in panel
    assert 'gps-bottom-speed-pair' in gauge and 'Row(' in gauge
    assert 'Color(0xFFFF3030)' in gauge and 'speedLimit.numberKmh' in gauge
    if not restored:
        for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main/assets'],text=True).splitlines():
            assert Path(path).read_bytes()==gitfilebytes(path),path
    return {'source_baseline_B880_tag_commit':BASE,
        'only_changed_paths':sorted(changes),'B880_compact_controls_unchanged':True,
        'floating_speed_meters_removed':True,'both_meters_horizontal_inside_bottom_panel':True,
        'overspeed_red_879_data_flow_unchanged':True,'official_merge':False}
def gitfilebytes(path):
    return subprocess.check_output(['git','show',BASE+':'+path])
def restore(path):
    assert sha(Path(path).read_bytes())==B879_OFFICIAL_APK_SHA256
    with zipfile.ZipFile(path) as z:
        names=assets(z);assert len(names)==436,len(names)
        for name in names:
            assert '..' not in Path(name).parts
            target=Path('app/src/main')/name
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(z.read(name))
    print('Restored 436 byte-identical B879 Official assets')
def totals():
    x=dict(tests=0,errors=0,failures=0,skipped=0)
    for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        root=ET.parse(f).getroot()
        for k in x:x[k]+=int(root.get(k,0))
    return x
def verify(original,testing,out):
    audit_data=audit(restored=True)
    assert sha(Path(original).read_bytes())==B879_OFFICIAL_APK_SHA256
    out=Path(out);out.mkdir(exist_ok=True,parents=True)
    with zipfile.ZipFile(original) as a,zipfile.ZipFile(testing) as b:
        assert b.testzip() is None
        an=assets(a);bn=assets(b);assert len(an)==436 and bn==an
        for name in an:assert a.read(name)==b.read(name),name
        manifest=json.loads(b.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        assert len([n for n in an if n.endswith('.mp3')])==209
        assert len([n for n in an if n.endswith('.pcm')])==209
        assert len(json.loads(b.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
    test=totals()
    assert test['tests']>=698 and not any(test[k] for k in ('failures','errors','skipped')),test
    report={'testing_build':881,'version_name':'1.4.69-b881-testing','version_code':89,
      'package_id':'com.izzyan.sgdeliveryplanner.b881test',
      'speed_meters':'horizontal side-by-side within opaque bottom arrival panel',
      'floating_speed_meter':'REMOVED','compact_right_controls':'B880 preserved',
      'original_assets_identical':436,'erp_records':106,'erp_map_locations':101,
      'original_panda_mp3_and_pcm':209,'camera_records':341,
      'unit_tests':test,'source_audit':audit_data,
      'testing_apk_sha256':sha(Path(testing).read_bytes()),
      'physical_phone_UI_and_driving_test':'NOT PERFORMED'}
    (out/'B881-Verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Usage: source | restore official.apk | verify official.apk testing.apk output')
