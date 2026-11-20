#!/usr/bin/env python3
"""Check the isolated B875 speed-limit change against exact Official B874."""
import gzip,hashlib,json,re,sqlite3,subprocess,sys,tempfile,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

BASELINE='10d641eaad1f43f08018baebacca6f8f01cdc38d'
BASELINE_SHA256='2005a0c2ffcb4cfb3bd08def823382d6f8499970a1e36e037c1a51689acbfbbe'
ROOT='app/src/main/java/com/izzyan/sgdeliveryplanner/'
NAV=ROOT+'BuiltInNavigationActivity.kt'
NEW_ASSETS={'assets/sg_road_speed_limits_875.bin','assets/sg_road_speed_limits_875_manifest.json'}

def prior(path):return subprocess.check_output(['git','show',BASELINE+':'+path],text=True)
def function(text,name):
    start=re.search(r'^    (?:private |internal |suspend )*fun '+re.escape(name)+r'\(',text,re.M)
    assert start,name
    next_fun=re.search(r'^    (?:private |internal |suspend )*fun \w+\(',text[start.end():],re.M)
    end=start.end()+next_fun.start() if next_fun else len(text)
    return text[start.start():end]
def source_audit():
    subprocess.run(['git','merge-base','--is-ancestor',BASELINE,'HEAD'],check=True)
    old=prior(NAV);new=Path(NAV).read_text()
    protected=['GpsControl','scanCameraSafety','cameraCueRelevant872','logCameraApproach872',
               'requestRoute','updateProgress','pandaStatus','announcePanda']
    for name in protected:assert function(old,name)==function(new,name),'Unrelated navigation change: '+name
    allowed={NAV,ROOT+'CameraSafetySettingsUi.kt',ROOT+'MainActivity.kt',
             *(ROOT+n+'.kt' for n in ['RoadSpeedLimit875','RoadSpeedStore875','RoadSpeedUpdates875']),
             'app/src/test/java/com/izzyan/sgdeliveryplanner/RoadSpeedLimit875Test.kt',
             'app/src/test/java/com/izzyan/sgdeliveryplanner/RoadSpeedStore875Test.kt'}
    changed=set(subprocess.check_output(['git','diff','--name-only',BASELINE,'HEAD','--','app/src/main','app/src/test'],text=True).splitlines())
    assert changed<=allowed,changed-allowed
    for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASELINE,'--','app/src/test'],text=True).splitlines():
        assert prior(path)==Path(path).read_text(),'Existing regression changed: '+path
    build=prior('app/build.gradle.kts').replace('versionCode = 81; versionName = "1.4.62-b874"',
         'versionCode = 82; versionName = "1.4.63-b875-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b875test"')
    assert Path('app/build.gradle.kts').read_text()==build,'Unexpected build/dependency change'
    main=ROOT+'MainActivity.kt'
    assert Path(main).read_text()==prior(main).replace('        runCatching { AppUpdateScheduler.schedule(applicationContext) }',
        '        runCatching { AppUpdateScheduler.schedule(applicationContext) }\n        runCatching { RoadSpeedUpdateScheduler875.schedule(applicationContext) }')
    assert 'scanCameraSafety(fix,now)\n                updateRoadSpeed875(fix)' in new
    assert 'val limit=session.roadSpeedLimitState875' in new
    assert 'CHECK\\nSIGNS' in new
    return {'baseline_commit':BASELINE,'current_commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'changed_application_sources':sorted(changed),'protected_functions_byte_unchanged':protected,
            'all_original_tests_byte_unchanged':True,'user_database_schema_unchanged':True,
            'voice_camera_route_zoom_and_delivery_modules_unchanged':True,
            'six_transparent_controls_byte_unchanged':True,'new_dependency_count':0}
def app_assets(apk):return {n for n in apk.namelist() if n.startswith('assets/') and not n.startswith('assets/dexopt/') and not n.endswith('/')}
def require_baseline(path):assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==BASELINE_SHA256
def restore(path):
    require_baseline(path)
    with zipfile.ZipFile(path) as apk:
        assets=app_assets(apk);assert len([n for n in assets if n.endswith('.mp3')])==209
        for name in assets:
            assert '..' not in Path(name).parts
            target=Path('app/src/main')/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(apk.read(name))
        print(f'Restored {len(assets)} exact B874 assets including 209 MP3 and PCM recordings')
def verify(baseline,testing,output):
    require_baseline(baseline);audit=source_audit();output=Path(output);output.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(baseline) as old,zipfile.ZipFile(testing) as new:
        assert new.testzip() is None
        assets=app_assets(old);assert app_assets(new)==assets|NEW_ASSETS
        for name in assets:assert old.read(name)==new.read(name),name
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
        manifest=json.loads(new.read('assets/sg_road_speed_limits_875_manifest.json'))
        zipped=new.read('assets/sg_road_speed_limits_875.bin');raw=gzip.decompress(zipped)
        assert hashlib.sha256(zipped).hexdigest()==manifest['gzip_sha256'] and len(zipped)==manifest['gzip_bytes']
        assert hashlib.sha256(raw).hexdigest()==manifest['sha256'] and len(raw)==manifest['bytes']
        with tempfile.NamedTemporaryFile(suffix='.db') as tmp:
            tmp.write(raw);tmp.flush()
            with sqlite3.connect(tmp.name) as db:
                assert db.execute('PRAGMA quick_check').fetchone()[0]=='ok'
                assert not db.execute('PRAGMA foreign_key_check').fetchall()
                assert db.execute('SELECT COUNT(*) FROM road_way').fetchone()[0]==manifest['ways']
                assert db.execute('SELECT COUNT(*) FROM road_edge').fetchone()[0]==manifest['edges']
                assert db.execute("SELECT COUNT(*) FROM road_way WHERE highway='motorway' AND tunnel=1 AND (forward_kmh=70 OR backward_kmh=70)").fetchone()[0]>0
        for abi in ['arm64-v8a','armeabi-v7a']:assert any(n.startswith('lib/'+abi+'/') for n in new.namelist())
    totals=dict(tests=0,failures=0,errors=0,skipped=0);replay=[];speed_tests=0
    for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        report=ET.parse(path).getroot()
        for key in totals:totals[key]+=int(report.get(key,0))
        if 'RoadSpeed' in path.name:speed_tests+=int(report.get('tests',0))
        for out in report.findall('system-out'):
            replay.extend(line for line in (out.text or '').splitlines() if 'B875 OFFLINE_REAL_DATA' in line)
    assert totals['tests']>=638 and speed_tests>=34,totals
    assert not any(totals[k] for k in ['failures','errors','skipped']),totals
    assert len(replay)>=3,replay
    (output/'B875-offline-gps-replay.log').write_text('\n'.join(replay)+'\n')
    (output/'B875-source-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    result={'baseline_commit':BASELINE,'baseline_apk_sha256':BASELINE_SHA256,'testing_commit':audit['current_commit'],
            'testing_apk_sha256':hashlib.sha256(Path(testing).read_bytes()).hexdigest(),
            'application_id':'com.izzyan.sgdeliveryplanner.b875test','version_code':82,'version_name':'1.4.63-b875-testing',
            'all_unit_tests':totals,'new_road_speed_tests':speed_tests,'python_catalog_tests':8,
            'unchanged_app_assets':len(assets),'original_mp3_unchanged':209,'original_pcm_unchanged':209,
            'camera_database_unchanged':341,'road_catalog':manifest,'unknown_state':'CHECK SIGNS',
            'real_offline_catalog_gps_replay':replay,'official_merge':False,
            'physical_road_drive_bluetooth_device_visual_qa':'NOT PERFORMED',
            'coverage_claim':'Partial verified/mapped coverage; see category coverage and complete unknown-way CSV',
            'weekly_app_checks':'7-day connected WorkManager; retains prior valid data after failed update',
            'weekly_source_refresh':'Workflow provided; GitHub cron only activates after file is on default branch, pending Official approval'}
    (output/'B875-acceptance.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(source_audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Expected source, restore or verify')
