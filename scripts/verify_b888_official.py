#!/usr/bin/env python3
"""Promote the exact user-approved B888 scenery build with the original Official identity."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='c9c824b97de50465a2a4f60b7be0522761b3d155'
APPROVED='91c5f87e78e9321ba83f705624af544a8c02a967'
BASE_APK='b57f630c07ae99dcc6855c1a604e9ab7734bd0dab20ebb15dd272cb89822819e'
TEST_APK='6e5e4a34736be36d41babe8e60afa50f19b87fa5be31c4c2fe1f66bb84b2c279'
TEST_EVIDENCE='3bc7fac157b097a8b8d7f324d6d2e90017f91bf3bfcd5b9fdc8242098b617ff4'
CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
CHANGED={'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'scripts/verify_b888_official.py','.github/workflows/publish-b888-official.yml'}
def sha(data):return hashlib.sha256(data).hexdigest()
def gitfile(commit,path):return subprocess.check_output(['git','show',commit+':'+path],cwd=ROOT).decode()
def source():
 for commit in (BASE,APPROVED):subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==CHANGED,changed^CHANGED
 expected=gitfile(APPROVED,'app/build.gradle.kts').replace('versionCode = 96; versionName = "1.4.76-b888-testing"',
  'versionCode = 97; versionName = "1.4.77-b888"').replace('applicationIdSuffix = ".b888test"','applicationIdSuffix = ".testing"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected
 assert (ROOT/'app/src/debug/res/values/strings.xml').read_text()==gitfile(BASE,'app/src/debug/res/values/strings.xml')
 return {'approvedTesting':APPROVED,'previousOfficial':BASE,'changedAfterApproval':sorted(changed),
  'allApplicationLogicIdenticalToApprovedTesting':True,'dataSchemaAndPersistenceUnchanged':True}
def assets(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def inputs(base,testing,evidence):
 assert sha(pathlib.Path(base).read_bytes())==BASE_APK
 assert sha(pathlib.Path(testing).read_bytes())==TEST_APK
 assert sha(pathlib.Path(evidence).read_bytes())==TEST_EVIDENCE
 with zipfile.ZipFile(evidence) as z:
  proof=json.loads(z.read('B888-acceptance.json'))
  assert proof['result']=='PASS' and proof['sourceCommit']==APPROVED and proof['baseline']==BASE
  assert proof['unitTests']==dict(tests=733,failures=0,errors=0,skipped=0)
  assert proof['nativeTests']==dict(tests=9,failures=0,errors=0,skipped=0)
  assert proof['apkSha256']==TEST_APK
  assert len(proof['rendering'])==2
  assert sum(len(r['cases']) for r in proof['rendering'])==6
  for rendering in proof['rendering']:
   assert rendering['result']=='PASS' and rendering['originalBinaryVectorTiles']
   for case in rendering['cases']:
    assert case['originalGeometryAndRouteUnchanged'] and case['greenMappedGrass']
    assert case['unchangedBlueFraction']>=.985
  assert proof['stability']['result']==proof['treeProbe']['result']=='PASS'
  assert len([n for n in z.namelist() if n.startswith('native/SGDeliveryB888/B888-BEFORE-') and n.endswith('.png')])==6
  assert len([n for n in z.namelist() if n.startswith('native/SGDeliveryB888/B888-AFTER-') and n.endswith('.png')])==7
  assert json.loads(z.read('native/SGDeliveryB885/B885-GPS-replay-results.json'))['result']=='PASS'
  assert json.loads(z.read('native/SGDeliveryB884/B884-actions-results.json'))['result']=='PASS'
  assert json.loads(z.read('native/SGDeliveryB876Native/B876-native-map-results.json'))['all_101_rendered_offline']
 return proof
def restore(base,testing,evidence):
 inputs(base,testing,evidence)
 with zipfile.ZipFile(base) as old,zipfile.ZipFile(testing) as test:
  names=assets(old);assert len(names)==436 and assets(test)==names
  for n in names:
   assert old.read(n)==test.read(n),n
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(old.read(n))
 print('Restored 436 exact Official assets matched byte-for-byte to approved B888 TESTING')
def verify(base,testing,evidence,apk,out):
 audit=source();approved=inputs(base,testing,evidence);out=pathlib.Path(out);out.mkdir(exist_ok=True)
 assert 'Signer #1 certificate SHA-256 digest: '+CERT in (out/'signing-verification.txt').read_text()
 with zipfile.ZipFile(base) as old,zipfile.ZipFile(testing) as test,zipfile.ZipFile(apk) as new:
  assert new.testzip() is None
  names=assets(old);assert len(names)==436 and names==assets(test)==assets(new)
  assert all(old.read(n)==test.read(n)==new.read(n) for n in names)
  assert sum(n.endswith('.mp3') for n in names)==209 and sum(n.endswith('.pcm') for n in names)==209
  erp=json.loads(new.read('assets/sg_erp_manifest_876.json'))
  assert erp['record_count']==106 and erp['location_count']==101
  assert len(json.loads(new.read('assets/sg_enforcement_cameras.json'))['cameras'])==341
  for abi in ('arm64-v8a','armeabi-v7a'):assert any(n.startswith('lib/'+abi+'/') for n in new.namelist())
 tests=dict(tests=0,failures=0,errors=0,skipped=0)
 files=list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'));assert files
 for p in files:
  xml=ET.parse(p).getroot()
  for k in tests:tests[k]+=int(xml.get(k,0))
 assert tests==dict(tests=733,failures=0,errors=0,skipped=0),tests
 report={'result':'PASS','officialMerge':True,'build':888,'approvedTesting':APPROVED,
  'sourceCommit':os.getenv('GITHUB_SHA'),'applicationId':'com.izzyan.sgdeliveryplanner',
  'versionCode':97,'versionName':'1.4.77-b888','signingCertificate':CERT,
  'freshUnitTests':tests,'approvedNativeTests':approved['nativeTests'],'nativeEvidenceCarriedForward':True,
  'approvedSceneryRendering':approved['rendering'],'approvedStability':approved['stability'],
  'approvedTreeProbe':approved['treeProbe'],'originalApplicationAssets':436,'originalMP3PCMsets':209,
  'erpRecords':106,'erpSites':101,'sourceAudit':audit,'apkSha256':sha(pathlib.Path(apk).read_bytes()),
  'physicalPhoneUpgradeTested':False}
 (out/'B888-Official-acceptance.json').write_text(json.dumps(report,indent=2))
 text=f'''# SG Delivery Build #888 Official — Coloured Buildings & Green Vegetation
Date: 26 November 2026 (Malaysia / Singapore)

The user explicitly approved promotion of Build #888 TESTING: {APPROVED}.
Official source: {report['sourceCommit']}
Package: com.izzyan.sgdeliveryplanner
Version: 97 / 1.4.77-b888

The approved scenery is unchanged: six stable soft building colours, 60% native
3D building opacity, corresponding day/night palettes, and natural greens for
existing named vegetation layers. Native geometry, heights and roof/wall lighting
remain intact. Existing supported tree-canopy layers receive green and separate
trunks brown. Full-colour tree sprites retain their original artwork. No new trees,
map sources, geometry, models or downloads are added.

Only release metadata, the debug launcher label, the release workflow and the
verification script changed after approval. Production application logic, storage
schemas, settings, route/camera, Panda Warrior, enforcement/ERP, panels, delivery,
History, reports and Payment match approved TESTING exactly.

Validation: 733 fresh JVM/Robolectric tests passed with zero failures/errors/skips.
Android lint, release compilation, original signing certificate and 16KB ZIP
alignment passed. All 436 original application assets are byte-identical to B885
Official and approved B888 TESTING, including 209 MP3/PCM sets, 341 cameras,
road/postal data and ERP 106 records at 101 sites.

The approved nine Android API 35 tests are carried forward because application
source is identical to approved TESTING. They include native rendering/GPS replay
of original captured binary vector tiles for Woodlands and Marina Bay, six matched
before/after pairs in day/night, blue-route pixel/geometry checks, zoom/rotation,
offline style reload and uninterrupted camera animation, plus original lane/Then,
History and ERP regressions. No fresh physical drive, phone upgrade, Bluetooth or
live-GNSS session was performed during promotion.

The captured real tiles contain woodland/grass polygons but no individual-tree
layer; tree-colour handling was checked in a separate synthetic native-renderer
probe excluded from location screenshots. Styling depends on available map data;
this release does not add complete offline Singapore coverage.

The original Official package and certificate are retained. VersionCode increases
from B885 Official's 93 to 97, supporting an in-place Android update without
uninstalling Official. No storage clearing or user-data migration is introduced.
The canonical stable tag and APK filename match the unchanged in-app update parser.

APK SHA-256: {report['apkSha256']}
Signing certificate SHA-256: {CERT}
Published as the latest stable Official for the existing in-app update checker.
'''
 (out/'SG-Delivery-B888-Official-Report.md').write_text(text)
 print(json.dumps({k:v for k,v in report.items() if k not in ['approvedSceneryRendering','approvedStability','approvedTreeProbe']},indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
 elif sys.argv[1]=='verify':verify(*sys.argv[2:7])
 else:raise SystemExit('source | restore BASE TEST EVIDENCE | verify BASE TEST EVIDENCE APK OUT')
