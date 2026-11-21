#!/usr/bin/env python3
"""Strict B883 TESTING verification: B882 baseline, dual 52dp number-only circles and original APK assets."""
import hashlib,json,subprocess,sys,zipfile,xml.etree.ElementTree as ET
from pathlib import Path

BASE='34b9930152117ef46374144d86d3bf75c0ad0505'
BASE_APK_SHA256='b0849ce14b57c9bc0e449d586e060bbe9e0288e9d2f1f9d1192900b679f87504'
CHANGED={
 'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/DualCircleSpeed883Test.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881Test.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/SpeedLimitSign882Test.kt',
 'scripts/verify_b883_testing.py','.github/workflows/build-b883-testing.yml'
}
def sha(data):return hashlib.sha256(data).hexdigest()
def files(apk):return {s for s in apk.namelist() if s.startswith('assets/') and not s.endswith('/') and not s.startswith('assets/dexopt/')}
def gitfile(path):return subprocess.check_output(['git','show',BASE+':'+path],text=True)
def audit(restored=False):
    subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True)
    changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],text=True).splitlines())
    assert changed==CHANGED,('Unapproved source changes:',sorted(changed^CHANGED))
    original=gitfile('app/build.gradle.kts')
    expected=original.replace('versionCode = 90; versionName = "1.4.70-b882-testing"',
                              'versionCode = 91; versionName = "1.4.71-b883-testing"').replace(
                              'applicationIdSuffix = ".b882test"','applicationIdSuffix = ".b883test"')
    assert expected!=original and expected==Path('app/build.gradle.kts').read_text()
    s=Path('app/src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomSpeed881.kt').read_text()
    assert 'SPEED_WIDTH_DP=64' in s and 'SPEED_LIMIT_WIDTH_DP=64' in s
    assert 'CURRENT_SPEED_SIGN_DP=SPEED_LIMIT_SIGN_DP' in s
    assert 'SPEED_LIMIT_SIGN_DP=52' in s
    assert 'testTag("gps-bottom-speed-pair")' in s
    assert 'verticalAlignment=Alignment.CenterVertically' in s
    assert 'testTag("gps-bottom-current-speed-circle")' in s
    assert 'testTag("gps-bottom-speed-limit-sign")' in s
    assert 'Text("km/h"' not in s
    assert 'shape=RoundedCornerShape' not in s
    assert 'BorderStroke(3.dp,SGColors.Gold)' in s
    assert 'shape=CircleShape' in s
    assert 'Color(0xFFFF3030)' in s
    assert 'currentSpeedKmh?.coerceAtLeast(0)?.toString()' in s
    assert 'SpeedLimitSign882.fontSizeSp(currentSpeedKmh.coerceAtLeast(0)).sp' in s
    assert 'fontSize=SpeedLimitSign882.fontSizeSp(limitText.toInt()).sp' in s
    sign=s.split('// Only a genuine numeric limit may appear in the speed-limit sign.')[1]
    assert 'speedLimit.badgeLabel' not in sign and 'speedLimit.note' not in sign
    assert 'MAP DATA' not in sign
    assert 'SG Delivery #883 TESTING' in Path('app/src/debug/res/values/strings.xml').read_text()
    if not restored:
        originals=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE,'--','app/src/main/assets'],text=True).splitlines()
        for path in originals:
            assert Path(path).read_bytes()==subprocess.check_output(['git','show',BASE+':'+path]),path
    return {'baseline_source':BASE,'targeted_changes':sorted(changed),
            'exact_circle_size_dp':52,'meter_layout':'one Row, centered vertically, equal-width lanes',
            'speed_number_only':True,'speed_limit_number_only':True,
            'red_overspeed_color_preserved':True,'no_official_merge':True}
def restore(path):
    assert sha(Path(path).read_bytes())==BASE_APK_SHA256
    with zipfile.ZipFile(path) as z:
        names=files(z);assert len(names)==436,len(names)
        for name in names:
            assert '..' not in Path(name).parts
            dest=Path('app/src/main')/name;dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_bytes(z.read(name))
    print('Restored all 436 byte-identical B882 TESTING assets')
def test_counts():
    x=dict(tests=0,errors=0,failures=0,skipped=0)
    for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        root=ET.parse(f).getroot()
        for key in x:x[key]+=int(root.get(key,0))
    return x
def verify(old_path,new_path,out_dir):
    src=audit(restored=True)
    assert sha(Path(old_path).read_bytes())==BASE_APK_SHA256
    out=Path(out_dir);out.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(old_path) as old,zipfile.ZipFile(new_path) as new:
        assert new.testzip() is None
        a=files(old);b=files(new)
        assert len(a)==436 and a==b,(len(a),len(b))
        for name in a:assert old.read(name)==new.read(name),name
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        assert len([name for name in a if name.endswith('.mp3')])==209
        assert len([name for name in a if name.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
    tests=test_counts()
    assert tests['tests']>=711 and all(tests[k]==0 for k in ('failures','errors','skipped')),tests
    result={'build':883,'package':'com.izzyan.sgdeliveryplanner.b883test',
        'version':'1.4.71-b883-testing','circle_diameter_dp_each':52,
        'circle_alignment':'side-by-side same Row with shared vertical centerline',
        'current_speed':'number only; white normal, #FF3030 overspeed',
        'speed_limit':'number only; original white circle red rim',
        'all_436_original_assets_unchanged':True,'ERP_records':106,'ERP_locations':101,
        'Panda_MP3_PCM_sets':209,'camera_records':341,
        'tests':tests,'source_audit':src,
        'baseline_APK_sha256':BASE_APK_SHA256,'B883_APK_sha256':sha(Path(new_path).read_bytes()),
        'physical_device_screen_alignment_and_audio_QA':'NOT PERFORMED'}
    (out/'B883-Verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('source | restore B882.apk | verify B882.apk B883.apk output')
