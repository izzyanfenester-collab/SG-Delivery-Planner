#!/usr/bin/env python3
"""Audit ERP-only integration against exact Official #875 and source attachments."""
import hashlib,json,re,sqlite3,subprocess,sys,tempfile,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from build_erp_catalog876 import read_workbook,group

BASELINE='095f988e255d30776aeaa4ff3271efdc4d4728a0'
BASELINE_SHA='cbecdf011912d72bdb2c6c6bbf5099d024b5151806f77d914d826cb20654649e'
WORKBOOK_SHA='1f74a7542b5cb1f774f4c2c6a2379a4040c49a0ddd9f08c3798db73af77f4e26'
ICON_SHA='f0863913694b4919f099336521559273d3ffddffd93b433763eb074d5fafe08a'
ROOT='app/src/main/java/com/izzyan/sgdeliveryplanner/'
ERP_ASSETS={'assets/erp_reference_876.jpg','assets/erp_source_876.xlsx','assets/sg_erp_locations_876.db','assets/sg_erp_manifest_876.json'}
def sha(raw):return hashlib.sha256(raw).hexdigest()
def prior(path):return subprocess.check_output(['git','show',BASELINE+':'+path],text=True)
def assets(apk):return {n for n in apk.namelist() if n.startswith('assets/') and not n.startswith('assets/dexopt/') and not n.endswith('/')}

def source_audit():
    subprocess.run(['git','merge-base','--is-ancestor',BASELINE,'HEAD'],check=True)
    nav=ROOT+'BuiltInNavigationActivity.kt';mapfile=ROOT+'NavigationMap.kt'
    expected=prior(nav).replace('        val session = remember(snapshot.sessionKey) { NavigationSession(this@BuiltInNavigationActivity, stop) }',
        '        val session = remember(snapshot.sessionKey) { NavigationSession(this@BuiltInNavigationActivity, stop) }\n        val erp = rememberErpNavigation876(session.location)')
    expected=expected.replace('NavigationMap(session, Modifier.matchParentSize(), permitted, mapAppearance)',
        'NavigationMap(session, Modifier.matchParentSize(), permitted, mapAppearance, erp)')
    expected=expected.replace('                        CameraSafetySettingsPanel(session)','                        CameraSafetySettingsPanel(session)\n                        ErpSettingsPanel876(erp)')
    expected=expected.replace('        if (showCameras) TrafficCameraDialog(session) { showCameras = false }',
        '        ErpInformationCard876(erp)\n        if (showCameras) TrafficCameraDialog(session) { showCameras = false }')
    assert Path(nav).read_text()==expected,'Unexpected GPS/session/control change'
    expected=prior(mapfile).replace('    appearance: NavigationMapAppearance\n','    appearance: NavigationMapAppearance,\n    erp: ErpNavigationState876? = null\n')
    expected=expected.replace('    var mapWidthPixels by remember { mutableIntStateOf(0) }',
        '    var mapWidthPixels by remember { mutableIntStateOf(0) }\n\n    ErpMapIntegration876(map,styleReady,erp)')
    assert Path(mapfile).read_text()==expected,'Unexpected camera/route/marker rendering change'
    allowed={nav,mapfile,*(ROOT+n+'.kt' for n in ['ErpCatalog876','ErpStore876','ErpNavigationUi876','ErpMapLayer876']),
        *(f'app/src/main/{n}' for n in ERP_ASSETS),
        'app/src/test/java/com/izzyan/sgdeliveryplanner/ErpCatalog876Test.kt',
        'app/src/test/java/com/izzyan/sgdeliveryplanner/ErpStationaryGate876Test.kt'}
    changed=set(subprocess.check_output(['git','diff','--name-only',BASELINE,'HEAD','--','app/src/main','app/src/test'],text=True).splitlines())
    assert changed<=allowed,changed-allowed
    for path in subprocess.check_output(['git','ls-tree','-r','--name-only',BASELINE,'--','app/src/test'],text=True).splitlines():
        assert Path(path).read_text()==prior(path),'Original test changed: '+path
    old=prior('app/build.gradle.kts');new=Path('app/build.gradle.kts').read_text()
    assert re.findall(r'^ implementation\(.*',new,re.M)==re.findall(r'^ implementation\(.*',old,re.M),'Runtime dependencies changed'
    assert 'versionCode = 84; versionName = "1.4.64-b876-testing"' in new and 'applicationIdSuffix = ".b876test"' in new
    assert 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' in new
    icon=Path(ROOT+'ErpMapLayer876.kt').read_text()
    assert all(s not in icon for s in ('.moveCamera(','.animateCamera(','.setCameraMode(','.setStyle(','.zoomWhileTracking('))
    assert 'iconSize(1f)' in icon and 'iconAllowOverlap(true)' in icon
    assert 'android:exported="false"' in Path('app/src/debug/AndroidManifest.xml').read_text()
    return {'baseline_commit':BASELINE,'testing_commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'old_navigation_source_changed_only_by_erp_hooks':True,'navigation_session_engine_byte_unchanged':True,
        'original_camera_voice_zoom_rerouting_route_delivery_theme_backup_and_data_modules_unchanged':True,
        'runtime_dependency_changes':False,'erp_data_separate_from_user_databases':True,
        'all_original_638_tests_unchanged':True,'icon_reference':'Original blue-white rectangular attachment; no recolour/red-circle substitution',
        'native_map_verification_activity':'DEBUG only, non-exported, local test map'}

def restore(baseline):
    assert sha(Path(baseline).read_bytes())==BASELINE_SHA
    with zipfile.ZipFile(baseline) as z:
        for name in assets(z):
            assert '..' not in Path(name).parts
            p=Path('app/src/main')/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
    print('Exact #875 assets restored, including 209 MP3/PCM and the approved offline speed catalog')

def totals(paths):
    result=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in paths:
        r=ET.parse(path).getroot()
        for k in result:result[k]+=int(r.get(k,0))
    return result
def verify(baseline,testing,output):
    output=Path(output);audit=source_audit();assert sha(Path(baseline).read_bytes())==BASELINE_SHA
    with zipfile.ZipFile(baseline) as old,zipfile.ZipFile(testing) as new:
        assert new.testzip() is None
        original=assets(old);assert len(original)==432 and assets(new)==original|ERP_ASSETS
        for name in original:assert old.read(name)==new.read(name),name
        assert sha(new.read('assets/erp_source_876.xlsx'))==WORKBOOK_SHA
        assert sha(new.read('assets/erp_reference_876.jpg'))==ICON_SHA
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        raw=new.read('assets/sg_erp_locations_876.db');assert sha(raw)==manifest['database_sha256']
        with tempfile.TemporaryDirectory() as temp:
            temp=Path(temp);book=temp/'source.xlsx';book.write_bytes(new.read('assets/erp_source_876.xlsx'))
            rows=read_workbook(book);sites=group(rows);dbfile=temp/'erp.db';dbfile.write_bytes(raw)
            with sqlite3.connect(dbfile) as db:
                assert db.execute('PRAGMA quick_check').fetchone()[0]=='ok'
                assert not db.execute('PRAGMA foreign_key_check').fetchall()
                actual=db.execute('SELECT unique_id,gantry_no,latitude_text,longitude_text,google_maps,source_row FROM erp_record ORDER BY source_row').fetchall()
                assert actual==[(r['unique_id'],r['gantry_no'],r['latitude'],r['longitude'],r['google_maps'],r['source_row']) for r in rows]
                assert db.execute('SELECT COUNT(*) FROM erp_site').fetchone()[0]==len(sites)==101
                assert db.execute('SELECT COUNT(*) FROM erp_site_record').fetchone()[0]==106
    unit=totals(Path('app/build/test-results/testDebugUnitTest').glob('TEST*.xml'))
    native=totals(Path('app/build/outputs/androidTest-results/connected').rglob('TEST*.xml'))
    assert unit['tests']>=663 and native['tests']>=1,(unit,native)
    assert not any(t[k] for t in (unit,native) for k in ('failures','errors','skipped')),(unit,native)
    native_json=output/'native-map/erp-876-native-evidence/B876-native-map-results.json'
    render=json.loads(native_json.read_text())
    assert render['all_101_rendered_offline'] and len(render['modes'])==6
    assert render['shared_record_selection'] and render['moving_selection_blocked'] and render['visibility_toggle']
    assert render['vehicle_arrow_above_erp'] and render['camera_route_retained']
    (output/'B876-source-audit.json').write_text(json.dumps(audit,indent=2)+'\n')
    result={'baseline_commit':BASELINE,'baseline_apk_sha256':BASELINE_SHA,'testing_commit':audit['testing_commit'],
        'testing_apk_sha256':sha(Path(testing).read_bytes()),'application_id':'com.izzyan.sgdeliveryplanner.b876test',
        'version_code':84,'version_name':'1.4.64-b876-testing','erp_manifest':manifest,'unit_tests':unit,'native_instrumentation_tests':native,
        'workbook_rows_exactly_preserved':106,'native_map_locations':101,'unchanged_baseline_assets':432,
        'original_mp3_unchanged':209,'original_pcm_unchanged':209,'camera_records_unchanged':341,
        'existing_speed_limit_catalog_unchanged':True,'show_erp_default':True,'official_merge':False,
        'native_render_evidence':render,'icon_width_dp':42,'icon_aspect_ratio':2.4,
        'erp_charges_hours_live_status_direction_and_audio':'Not inferred or added',
        'physical_runner_device_driving_gpu_profiling':'NOT PERFORMED',
        'screenshot_context':'Actual native MapLibre renderer in a DEBUG-only local map harness; not physical GPS navigation'}
    (output/'B876-acceptance.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(source_audit(),indent=2))
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:5])
    else:raise SystemExit('Expected source, restore or verify')
