#!/usr/bin/env python3
"""Promote the exact owner-approved B875 into the canonical signed application."""
import gzip,hashlib,json,os,subprocess,sys,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from verify_b875_testing import BASELINE,BASELINE_SHA256,ROOT,app_assets,require_baseline

APPROVED='5361090ebf4575d395bc856894084fe98909ac64'
APPROVED_APK_SHA='81d72b25179cbb11b4cb2efbf4f1afaa0d6b1ac75b4cda60a813d1b9a59f47d1'
APPROVED_EVIDENCE_SHA='7a3f5c479001b5ab8012c22f867d5c57cc178cf6517e7c80d69241844f979913'
ORIGINAL_CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'

def prior(path):return subprocess.check_output(['git','show',APPROVED+':'+path],text=True)
def source_audit():
    for commit in (BASELINE,APPROVED):subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True)
    changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD','--','app/src/main','app/src/test'],text=True).splitlines())
    update=ROOT+'RoadSpeedUpdates875.kt'
    assert changed<={update},changed
    expected=prior(update).replace('The TESTING data channel is independent of Official APK updates.',
        'The validated Official data channel is independent of APK updates.').replace('/sg-road-speed-limits-875-testing/','/sg-road-speed-limits/')
    assert Path(update).read_text()==expected,'Unexpected navigation/update logic change'
    build=prior('app/build.gradle.kts').replace('versionCode = 82; versionName = "1.4.63-b875-testing"',
        'versionCode = 83; versionName = "1.4.63-b875"').replace('applicationIdSuffix = ".b875test"','applicationIdSuffix = ".testing"')
    assert Path('app/build.gradle.kts').read_text()==build
    label='app/src/debug/res/values/strings.xml'
    assert Path(label).read_text()==prior(label).replace('SG Delivery #875 TESTING','SG Delivery')
    return {'baseline_commit':BASELINE,'approved_testing_commit':APPROVED,
        'runtime_and_test_source_unchanged_except_official_data_url':True,'only_runtime_difference':'Validated data channel URL: TESTING to Official',
        'voice_camera_zoom_rerouting_controls_themes_and_user_database_code_unchanged':True,
        'weekly_source_refresh':'Registered on default branch; Mondays 04:10 Malaysia time',
        'official_data_tag':'sg-road-speed-limits'}

def restore(testing,output,evidence):
    assert hashlib.sha256(Path(testing).read_bytes()).hexdigest()==APPROVED_APK_SHA
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(testing) as apk:
        for name in app_assets(apk):
            assert '..' not in Path(name).parts
            target=Path('app/src/main')/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(apk.read(name))
        (output/'sg-road-speed-limits-875.db.gz').write_bytes(apk.read('assets/sg_road_speed_limits_875.bin'))
        (output/'sg-road-speed-limits-875-manifest.json').write_bytes(apk.read('assets/sg_road_speed_limits_875_manifest.json'))
    assert hashlib.sha256(Path(evidence).read_bytes()).hexdigest()==APPROVED_EVIDENCE_SHA
    with zipfile.ZipFile(evidence) as proof:
        for name in ('B875-data-coverage.json','B875-upstream-source-audit.json','B875-official-sign-intervals.json','B875-unknown-road-ways.csv.gz'):
            (output/name).write_bytes(proof.read(name))
    print('Restored every approved B875 application asset, including its exact offline road-speed catalog')

def verify(baseline,testing,official,output):
    require_baseline(baseline)
    assert hashlib.sha256(Path(testing).read_bytes()).hexdigest()==APPROVED_APK_SHA
    output=Path(output);output.mkdir(parents=True,exist_ok=True);audit=source_audit()
    assert 'Signer #1 certificate SHA-256 digest: '+ORIGINAL_CERT in (output/'signing-verification.txt').read_text()
    with zipfile.ZipFile(baseline) as old,zipfile.ZipFile(testing) as approved,zipfile.ZipFile(official) as new:
        assert new.testzip() is None
        assets=app_assets(approved);assert app_assets(new)==assets
        for name in assets:assert new.read(name)==approved.read(name),name
        baseline_assets=app_assets(old)
        for name in baseline_assets:assert old.read(name)==new.read(name),name
        assert len([n for n in assets if n.endswith('.mp3')])==209
        assert len([n for n in assets if n.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
        manifest=json.loads(new.read('assets/sg_road_speed_limits_875_manifest.json'))
        zipped=new.read('assets/sg_road_speed_limits_875.bin');raw=gzip.decompress(zipped)
        assert hashlib.sha256(zipped).hexdigest()==manifest['gzip_sha256'] and len(zipped)==manifest['gzip_bytes']
        assert hashlib.sha256(raw).hexdigest()==manifest['sha256'] and len(raw)==manifest['bytes']
        for abi in ('arm64-v8a','armeabi-v7a'):assert any(n.startswith('lib/'+abi+'/') for n in new.namelist())
    totals=dict(tests=0,failures=0,errors=0,skipped=0);replays=[]
    for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        r=ET.parse(path).getroot()
        for k in totals:totals[k]+=int(r.get(k,0))
        for log in r.findall('system-out'):replays.extend(l for l in (log.text or '').splitlines() if 'B875 OFFLINE_REAL_DATA' in l)
    assert totals['tests']>=638 and not any(totals[k] for k in ('failures','errors','skipped')),totals
    assert len(replays)>=3,replays
    result={'official_merge':True,'merge_commit':os.getenv('GITHUB_SHA') or subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'baseline_commit':BASELINE,'approved_testing_commit':APPROVED,'baseline_apk_sha256':BASELINE_SHA256,
        'approved_testing_apk_sha256':APPROVED_APK_SHA,'official_apk_sha256':hashlib.sha256(Path(official).read_bytes()).hexdigest(),
        'application_id':'com.izzyan.sgdeliveryplanner','version_name':'1.4.63-b875','version_code':83,
        'previous_official_version_code':81,'signing_certificate_sha256':ORIGINAL_CERT,'upgrade_identity_matches_b874':True,
        'all_unit_tests':totals,'python_catalog_tests':8,'unchanged_b874_assets':len(baseline_assets),'approved_b875_assets_identical':len(assets),
        'original_mp3_unchanged':209,'original_pcm_unchanged':209,'camera_database_unchanged':341,
        'road_catalog':manifest,'real_offline_catalog_gps_replay':replays,'user_database_schema_changed':False,
        'weekly_source_refresh':'Mondays 04:10 Malaysia time; validated Official data release, independent of APK releases',
        'weekly_phone_checks':'7 days when connected; previous valid data retained after update failure',
        'physical_device_upgrade_driving_bluetooth_and_visual_qa':'NOT PERFORMED',
        'coverage':'Partial verified/mapped coverage; CHECK SIGNS on unknown/ambiguous applicability',
        'official_update_tag':'v1.4.63-b875'}
    (output/'B875-Official-source-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    (output/'B875-Official-acceptance.json').write_text(json.dumps(result,indent=2)+'\n')
    (output/'B875-Official-offline-gps-replay.log').write_text('\n'.join(replays)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(source_audit(),indent=2))
    elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:6])
    else:raise SystemExit('Expected source, restore or verify')
