#!/usr/bin/env python3
"""Promote exactly the user-approved B885 route-lane and Then build to Official."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='db4686692aa41b6e9b083db65e9cb9bdf96a16c2'
APPROVED='6838bd94ee82dacf6eaf6739214a02816bde91d1'
BASE_APK='59918c58c7d87e55483328384b7cef757dfd2eb2549163da72f9c6795ee615bf'
TEST_APK='40f84d33c18c89d6108687acddedfcb351a293069d844322b688abcbf2fe08b3'
TEST_EVIDENCE='e06dcc7899a0e6ec6b84c964f0b527ad5c7027b7ba4a4734bd0dea992dbb8165'
CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
CHANGED={'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'scripts/verify_b885_official.py','.github/workflows/publish-b885-official.yml'}
def sha(data):return hashlib.sha256(data).hexdigest()
def gitfile(commit,path):return subprocess.check_output(['git','show',commit+':'+path],cwd=ROOT).decode()
def source():
 for commit in (BASE,APPROVED):subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==CHANGED,changed^CHANGED
 expected=gitfile(APPROVED,'app/build.gradle.kts').replace('versionName = "1.4.73-b885-testing"','versionName = "1.4.73-b885"').replace('applicationIdSuffix = ".b885test"','applicationIdSuffix = ".testing"')
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
  proof=json.loads(z.read('B885-acceptance.json'))
  assert proof['result']=='PASS' and proof['sourceCommit']==APPROVED and proof['baseline']==BASE
  assert proof['unitTests']==dict(tests=726,failures=0,errors=0,skipped=0)
  assert proof['nativeTests']==dict(tests=4,failures=0,errors=0,skipped=0)
  assert proof['apkSha256']==TEST_APK
  assert proof['replay']['result']==proof['layout']['result']=='PASS'
  assert len(proof['replay']['observations'])==4 and len(proof['layout']['cases'])==6
  assert len([n for n in z.namelist() if n.startswith('native/SGDeliveryB885/') and n.endswith('.png')])==9
  history=json.loads(z.read('native/SGDeliveryB884/B884-layout-results.json'))
  actions=json.loads(z.read('native/SGDeliveryB884/B884-actions-results.json'))
  assert history['result']==actions['result']=='PASS'
 return proof
def restore(base,testing,evidence):
 inputs(base,testing,evidence)
 with zipfile.ZipFile(base) as old,zipfile.ZipFile(testing) as test:
  names=assets(old);assert len(names)==436 and assets(test)==names
  for n in names:
   assert old.read(n)==test.read(n),n
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(old.read(n))
 print('Restored 436 exact Official assets matched byte-for-byte to approved B885 TESTING')
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
 assert tests==dict(tests=726,failures=0,errors=0,skipped=0),tests
 report={'result':'PASS','officialMerge':True,'build':885,'approvedTesting':APPROVED,
  'sourceCommit':os.getenv('GITHUB_SHA'),'applicationId':'com.izzyan.sgdeliveryplanner',
  'versionCode':93,'versionName':'1.4.73-b885','signingCertificate':CERT,
  'freshUnitTests':tests,'approvedNativeTests':approved['nativeTests'],'nativeEvidenceCarriedForward':True,
  'approvedReplayResults':approved['replay'],'approvedLayoutResults':approved['layout'],
  'originalApplicationAssets':436,'originalMP3PCMsets':209,'erpRecords':106,'erpSites':101,
  'sourceAudit':audit,'apkSha256':sha(pathlib.Path(apk).read_bytes()),'physicalPhoneUpgradeTested':False}
 (out/'B885-Official-acceptance.json').write_text(json.dumps(report,indent=2))
 text=f'''# SG Delivery Build #885 Official – Lane Guidance & Then Next Direction
Date: 22 November 2026 (Malaysia / Singapore)

The user explicitly approved promotion of Build #885 TESTING: {APPROVED}.
Official source: {report['sourceCommit']}
Package: com.izzyan.sgdeliveryplanner
Version: 93 / 1.4.73-b885

The approved lane guidance and Then indicator are unchanged. Valid lanes are
white, inactive branches are muted, physical lane order is preserved and missing
metadata hides the lane strip. The separate Then badge uses the actual following
actionable maneuver. Both invalidate on route/session changes, arrival and poor
or stale GPS. No lane-level vehicle-position claim or new voice event is added.

Only release metadata, the debug launcher label, the release workflow and this
verification script changed after approval. Application code and all settings,
Room/storage schemas, delivery/report/history/payment logic, GPS camera/routing,
Panda Warrior, enforcement and ERP implementations match approved TESTING exactly.

Validation: 726 fresh JVM/Robolectric tests passed, with zero failures, errors or
skips. Android lint, release compilation, original certificate and 16KB ZIP
alignment passed. All 436 application assets match #884 Official and approved
#885 TESTING byte-for-byte, including 209 MP3/PCM sets, 341 cameras, road/postal
data and ERP 106 records / 101 locations.

The approved native evidence is included unchanged: four Android API 35 tests,
nine actual GPS replay screenshots, white/muted pixel checks, shared-direction
lanes, maneuver advancement, missing/stale data, 320dp/400dp layouts and font
scales 1.0/1.5 in day/2D and night/3D local route views. Original History layout
and Room record viewing/selection/deletion/resume checks also passed. Its
synthetic first record uses the current Singapore date; all original benchmark
assertions remain in place. Native tests are carried forward because application
source is identical to approved TESTING; no fresh physical drive, phone upgrade,
Bluetooth or live-GNSS session was performed during promotion.

Coverage remains sampled rather than nationwide: provider lane metadata was
available at 15 of 20 maneuver locations across the two recorded Singapore
routes. Missing examples include the Upper Thomson exit, Island Club
continuation, initial Handy Road turn and two final unnamed turns. Ordinary
navigation remains visible there. Active loaded-route metadata works locally
without further requests; fresh offline route creation and local lane-aware
rerouting are not added. These availability limits are unchanged from TESTING.

The original Official package and signing certificate are retained. VersionCode
increases from #884's 92 to 93, supporting an in-place Android update without
uninstalling Official. No storage clearing or user-data migration is introduced.
The stable tag and canonical APK filename match the unchanged in-app update
checker's existing release parser.

APK SHA-256: {report['apkSha256']}
Signing certificate SHA-256: {CERT}
Published as the latest stable Official for the existing in-app update checker.
'''
 (out/'SG-Delivery-B885-Official-Report.md').write_text(text)
 print(json.dumps(report,indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
 elif sys.argv[1]=='verify':verify(*sys.argv[2:7])
 else:raise SystemExit('source | restore BASE TEST EVIDENCE | verify BASE TEST EVIDENCE APK OUT')
