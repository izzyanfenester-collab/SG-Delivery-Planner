#!/usr/bin/env python3
"""Validate user-approved B878+B879 fixes promoted to canonical signed B879 Official."""
import hashlib, json, os, sqlite3, subprocess, sys, tempfile, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

BASE='850dfe1f48b8e8f42ef607865ffd8e1ac6338ef3'
APPROVED='856034209ba3cabe3653355d3ae6194eb5c45038'
B877_OFFICIAL_SHA='b484fd62efef51f294ae6dbac472f2cb0ddf629ba8795f85c20a3c7ba924f5e5'
B879_TESTING_SHA='7a466a9f16696ba590ea44257a5082c4d507d230427aefbdeb1f5a7b1b16ace9'
EVIDENCE_SHA='580b3820b2b2621cb43e5247ba23dd32f86427b4ac03fb623c0f6a4b7198552b'
OFFICIAL_CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
WORKBOOK_SHA='1f74a7542b5cb1f774f4c2c6a2379a4040c49a0ddd9f08c3798db73af77f4e26'
EXPECTED_CHANGED={
    'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
    'scripts/verify_b879_official.py','.github/workflows/publish-b879-official.yml',
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
    assert changed==EXPECTED_CHANGED,('Unapproved changes after B879 TESTING:', sorted(changed^EXPECTED_CHANGED))
    approved=commitfile(APPROVED,'app/build.gradle.kts')
    expected=approved.replace('versionName = "1.4.67-b879-testing"',
                              'versionName = "1.4.67-b879"').replace(
        'applicationIdSuffix = ".b879test"','applicationIdSuffix = ".testing"')
    assert expected!=approved
    assert Path('app/build.gradle.kts').read_text()==expected
    assert Path('app/src/debug/res/values/strings.xml').read_text()==commitfile(
        BASE,'app/src/debug/res/values/strings.xml')
    return {'approved_testing_commit':APPROVED,'original_official_baseline':BASE,
        'only_changed_after_approval':sorted(changed),
        'gps_and_all_other_app_source_byte_identical_to_approved_B879_testing':True,
        'no_database_schema_changes_during_official_promotion':True}

def restore(old_path,testing_path,evidence_path):
    assert sha(Path(old_path).read_bytes())==B877_OFFICIAL_SHA
    assert sha(Path(testing_path).read_bytes())==B879_TESTING_SHA
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
    print('Restored all 436 verified identical assets from B877 Official, identical to approved B879 TESTING.')

def verify(old_path,testing_path,evidence_path,official_path,output_dir):
    audit=source()
    assert sha(Path(old_path).read_bytes())==B877_OFFICIAL_SHA
    assert sha(Path(testing_path).read_bytes())==B879_TESTING_SHA
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
    tests=dict(tests=0,failures=0,errors=0,skipped=0)
    for f in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        xml=ET.parse(f).getroot()
        for key in tests: tests[key]+=int(xml.get(key,0))
    assert tests['tests']>=684 and tests['failures']==0 and tests['errors']==0 and tests['skipped']==0,tests
    report={'official_merge':True,'approved_B879_testing_commit':APPROVED,
        'official_commit':os.getenv('GITHUB_SHA'),
        'package_name':'com.izzyan.sgdeliveryplanner',
        'version_code':87,'version_name':'1.4.67-b879',
        'signing_certificate_sha256':OFFICIAL_CERT,
        'B877_official_assets_identical':436,'B879_approved_testing_assets_identical':436,
        'erp_records':106,'erp_locations':101,'Panda_original_MP3_and_PCM_sets':209,
        'camera_database_records':341,'unit_tests':tests,
        'app_logic_identical_to_approved_testing':True,
        'B879_prerelease_evidence_sha256':EVIDENCE_SHA,
        'physical_road_and_bluetooth_tests':'NOT PERFORMED',
        'source_audit':audit,'official_apk_sha256':sha(Path(official_path).read_bytes())}
    (out/'B879-Official-acceptance.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
    elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
    elif sys.argv[1]=='verify':verify(*sys.argv[2:7])
    else:raise SystemExit('Usage: source | restore B877 B879 evidence | verify B877 B879 evidence official output')
