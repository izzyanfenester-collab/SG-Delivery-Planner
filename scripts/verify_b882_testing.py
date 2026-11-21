#!/usr/bin/env python3
"""B882 TESTING: verified B881 baseline, number-only speed-limit circle and intact app data."""
import hashlib,json,subprocess,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
BASE='e72276bb546e1aa90a3d7d9814ef5b0d6a0706c9'
B881_TESTING_SHA256='e2287b24ca3a6bbbc5c877e5d45be32c1ff9bad212aae29b07a2d0e47fafc083'
ALLOWED={
 'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/SpeedLimitSign882Test.kt',
 'scripts/verify_b882_testing.py','.github/workflows/build-b882-testing.yml'
}
def sha(b):return hashlib.sha256(b).hexdigest()
def assets(z):return {p for p in z.namelist() if p.startswith('assets/') and not p.endswith('/') and not p.startswith('assets/dexopt/')}
def gitfile(path):return subprocess.check_output(['git','show',BASE+':'+path],text=True)
def audit(restored=False):
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
    changes=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],text=True).splitlines())
    assert changes==ALLOWED,('Unapproved changes:',sorted(changes^ALLOWED))
    original=gitfile('app/build.gradle.kts')
    expected=original.replace('versionCode = 89; versionName = "1.4.69-b881-testing"',
                              'versionCode = 90; versionName = "1.4.70-b882-testing"').replace(
        'applicationIdSuffix = ".b881test"','applicationIdSuffix = ".b882test"')
    assert expected!=original and Path('app/build.gradle.kts').read_text()==expected
    label=Path('app/src/debug/res/values/strings.xml').read_text()
    assert 'SG Delivery #882 TESTING' in label
    gauge=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt').read_text()
    before=gitfile('app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt')
    assert 'SPEED_LIMIT_SIGN_DP=52' in gauge and 'SPEED_LIMIT_SIGN_DP=52' in before
    assert 'SPEED_LIMIT_WIDTH_DP=64' in gauge and 'SPEED_LIMIT_WIDTH_DP=64' in before
    assert 'SPEED_WIDTH_DP=70' in gauge and 'SPEED_WIDTH_DP=70' in before
    assert 'testTag("gps-bottom-speed-pair")' in gauge
    assert 'testTag("gps-bottom-speed-limit-sign")' in gauge
    assert 'Text("km/h"' in gauge # current vehicle speed continues to show its unit
    sign_block=gauge.split('// Only a genuine numeric limit may appear in the speed-limit sign.')[1]
    for removed in ('speedLimit.badgeLabel','speedLimit.note','Text("km/h"','"MAP DATA"','"CHECK\\nSIGNS"'):
        assert removed not in sign_block,('Unexpected content in speed-limit area',removed)
    assert 'fontSize=SpeedLimitSign882.fontSizeSp(limitText.toInt()).sp' in sign_block
    assert 'limitText' in sign_block and 'Text(' in sign_block
    assert 'Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center)' in sign_block
    assert 'Color(0xFFFF3030)' in gauge # original current speed overspeed red preserved
    assert 'if(showSpeedLimit && speedLimit.hasNumericLimit)' in sign_block
    if not restored:
        for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main/assets'],text=True).splitlines():
            assert Path(path).read_bytes()==subprocess.check_output(['git','show',BASE+':'+path]),path
    return {'approved_B881_testing_source':BASE,'changed_files':sorted(changes),
            'only_speed_limit_visual_changes':True,'circle_dp':52,
            'speed_limit_numeric_font_sp_two_digits':32,
            'hidden_labels':['km/h inside limit sign','MAP DATA','source date','source note'],
            'current_speed_unit_and_overspeed_color_preserved':True,
            'official_merge':False}
def restore(apk_path):
    assert sha(Path(apk_path).read_bytes())==B881_TESTING_SHA256
    with zipfile.ZipFile(apk_path) as z:
        names=assets(z)
        assert len(names)==436,len(names)
        for name in names:
            assert '..' not in Path(name).parts
            target=Path('app/src/main')/name;target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(z.read(name))
    print('Restored all 436 B881 TESTING bundled assets without change')
def test_counts():
    d=dict(tests=0,errors=0,failures=0,skipped=0)
    for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        t=ET.parse(f).getroot()
        for k in d:d[k]+=int(t.get(k,0))
    return d
def verify(source_apk,testing_apk,out_dir):
    audited=audit(restored=True)
    assert sha(Path(source_apk).read_bytes())==B881_TESTING_SHA256
    out=Path(out_dir);out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(source_apk) as old,zipfile.ZipFile(testing_apk) as new:
        assert new.testzip() is None
        original=assets(old);current=assets(new)
        assert len(original)==436 and original==current,(len(original),len(current))
        for name in original:assert old.read(name)==new.read(name),name
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        assert len([n for n in original if n.endswith('.mp3')])==209
        assert len([n for n in original if n.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
    tests=test_counts()
    assert tests['tests']>=704 and all(tests[k]==0 for k in ('errors','failures','skipped')),tests
    report={'testing_build':882,'version':'1.4.70-b882-testing','package':'com.izzyan.sgdeliveryplanner.b882test',
      'speed_limit_circle':'number only, enlarged and centered; original 52dp diameter',
      'bottom_horizontal_meters':'preserved','six_compact_gps_controls':'preserved',
      'voice_speed_and_gps_logic':'unchanged','original_app_assets_identical':436,
      'erp_source_records':106,'erp_locations':101,'panda_mp3_pcm_sets':209,
      'enforcement_camera_records':341,'tests':tests,'source_audit':audited,
      'B881_testing_apk_sha256':B881_TESTING_SHA256,
      'B882_testing_apk_sha256':sha(Path(testing_apk).read_bytes()),
      'physical_Android_UI_and_drive_QA':'NOT PERFORMED'}
    (out/'B882-Verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Expected source | restore b881.apk | verify b881.apk b882.apk out')
