#!/usr/bin/env python3
"""Promote exact owner-approved B876 ERP code and assets to signed Official."""
import hashlib,json,os,sqlite3,subprocess,sys,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from verify_b876_testing import BASELINE, BASELINE_SHA, ERP_ASSETS, assets

APPROVED='3969e7c2290254d4c4ddc40ed45318b04bd410d8'
APPROVED_APK_SHA='9bfaa1bd841b555fe081f53f4955d1153af2b5ef356bc5abef758162bfb09808'
APPROVED_EVIDENCE_SHA='67bb6e76c67f77e4561f1eecb660650cec048bca5d510c86ad04d3b3bc10b877'
CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
WORKBOOK_SHA='1f74a7542b5cb1f774f4c2c6a2379a4040c49a0ddd9f08c3798db73af77f4e26'

def sha(data): return hashlib.sha256(data).hexdigest()
def from_commit(commit,path):
    return subprocess.check_output(['git','show',commit+':'+path],text=True)
def source():
    for commit in (BASELINE,APPROVED):
        subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True)
    allowed={'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
             'scripts/verify_b876_official.py','.github/workflows/publish-b876-official.yml'}
    changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD'],text=True).splitlines())
    assert changed == allowed,('Unexpected or missing changes:',changed^allowed)
    approved=from_commit(APPROVED,'app/build.gradle.kts')
    expected=approved.replace('versionName = "1.4.64-b876-testing"','versionName = "1.4.64-b876"').replace('applicationIdSuffix = ".b876test"','applicationIdSuffix = ".testing"')
    assert expected!=approved
    assert Path('app/build.gradle.kts').read_text()==expected
    assert Path('app/src/debug/res/values/strings.xml').read_text()==from_commit(BASELINE,'app/src/debug/res/values/strings.xml')
    return {'approved_testing_commit':APPROVED,'baseline_official_commit':BASELINE,
            'changed_since_approved_testing':sorted(changed),'only_changes':'Official version, debug label, audit and release workflow',
            'erp_runtime_and_all_non_erp_source_unmodified':True,'user_database_schema_changed':False}

def restore(old_path,testing_path,evidence_path):
    assert sha(Path(old_path).read_bytes())==BASELINE_SHA
    assert sha(Path(testing_path).read_bytes())==APPROVED_APK_SHA
    assert sha(Path(evidence_path).read_bytes())==APPROVED_EVIDENCE_SHA
    with zipfile.ZipFile(old_path) as old:
        original=assets(old)
        assert len(original)==432,len(original)
        for path in original:
            assert '..' not in Path(path).parts
            target=Path('app/src/main')/path
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(old.read(path))
    print('Restored all 432 byte-identical Official B875 assets; ERP attachment files preserved')

def verify(old_path,testing_path,evidence_path,official_path,output):
    audit=source()
    assert sha(Path(old_path).read_bytes())==BASELINE_SHA
    assert sha(Path(testing_path).read_bytes())==APPROVED_APK_SHA
    assert sha(Path(evidence_path).read_bytes())==APPROVED_EVIDENCE_SHA
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    assert 'Signer #1 certificate SHA-256 digest: '+CERT in (output/'signing-verification.txt').read_text()
    with zipfile.ZipFile(old_path) as old,zipfile.ZipFile(testing_path) as testing,zipfile.ZipFile(official_path) as new:
        assert new.testzip() is None
        original=assets(old)
        new_assets=assets(new)
        assert len(original)==432 and new_assets==original|ERP_ASSETS,(len(original),len(new_assets))
        assert assets(testing)==new_assets
        for name in new_assets: assert new.read(name)==testing.read(name),name
        for name in original: assert new.read(name)==old.read(name),name
        assert sha(new.read('assets/erp_source_876.xlsx'))==WORKBOOK_SHA
        manifest=json.loads(new.read('assets/sg_erp_manifest_876.json'))
        assert manifest['record_count']==106 and manifest['location_count']==101
        db=new.read('assets/sg_erp_locations_876.db')
        assert sha(db)==manifest['database_sha256']
        with __import__('tempfile').TemporaryDirectory() as temp:
            filename=Path(temp)/'erp.db';filename.write_bytes(db)
            with sqlite3.connect(filename) as connection:
                assert connection.execute('PRAGMA quick_check').fetchone()[0]=='ok'
                assert connection.execute('SELECT COUNT(*) FROM erp_record').fetchone()[0]==106
                assert connection.execute('SELECT COUNT(*) FROM erp_site').fetchone()[0]==101
        assert len([n for n in original if n.endswith('.mp3')])==209
        assert len([n for n in original if n.endswith('.pcm')])==209
        assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
        for abi in ('arm64-v8a','armeabi-v7a'):
            assert any(n.startswith('lib/'+abi+'/') for n in new.namelist())
    total=dict(tests=0,failures=0,errors=0,skipped=0)
    for file in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        root=ET.parse(file).getroot()
        for key in total:total[key]+=int(root.get(key,0))
    assert total['tests']>=660 and not any(total[x] for x in ('failures','errors','skipped')),total
    report={'official_merge':True,'commit':os.getenv('GITHUB_SHA'),'application_id':'com.izzyan.sgdeliveryplanner',
      'version_code':84,'version_name':'1.4.64-b876','signing_certificate_sha256':CERT,
      'original_official_assets_identical':432,'approved_testing_assets_identical':436,
      'erp_source_records':106,'erp_distinct_locations':101,'original_mp3_and_pcm_clips_unchanged':209,
      'speed_and_red_light_camera_records_unchanged':341,'unit_tests':total,
      'approved_native_renderer_evidence_sha256':APPROVED_EVIDENCE_SHA,
      'physical_device_drive_test':'NOT PERFORMED','source_audit':audit,
      'official_apk_sha256':sha(Path(official_path).read_bytes())}
    (output/'B876-Official-acceptance.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    action=sys.argv[1]
    if action=='source': print(json.dumps(source(),indent=2))
    elif action=='restore':restore(*sys.argv[2:5])
    elif action=='verify':verify(*sys.argv[2:7])
    else:raise SystemExit('Usage: source | restore B875 B876 evidence | verify B875 B876 evidence official output')
