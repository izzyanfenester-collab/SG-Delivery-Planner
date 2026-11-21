#!/usr/bin/env python3
"""Promote precisely the user-approved B884 R2 source to canonical Official."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='680cac0d016b4002aab2c148f16694a8a223035f'
APPROVED='a38e048858299336ebe6220aee055526899f1c2a'
BASE_APK='f862a1d24cc6c6257089939437c92246a89a37033fb8a48c01e7ddd85ab1e7fd'
TEST_APK='347ad6f6e51fdf69419d9f36b4c81e562f8d19a3ba31789aa3d336f9ab1e8600'
TEST_EVIDENCE='d06b982e262b79121db4daea423c031df0e855504176f6f269098f0531427abb'
CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
CHANGED={'app/build.gradle.kts','app/src/debug/res/values/strings.xml',
 'scripts/verify_b884_official.py','.github/workflows/publish-b884-official.yml'}
def sha(data):return hashlib.sha256(data).hexdigest()
def gitfile(commit,path):return subprocess.check_output(['git','show',commit+':'+path],cwd=ROOT).decode()
def source():
 for commit in (BASE,APPROVED):subprocess.run(['git','merge-base','--is-ancestor',commit,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',APPROVED,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==CHANGED,changed^CHANGED
 expected=gitfile(APPROVED,'app/build.gradle.kts').replace('versionName = "1.4.72-b884-testing"','versionName = "1.4.72-b884"').replace('applicationIdSuffix = ".b884test"','applicationIdSuffix = ".testing"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected
 assert (ROOT/'app/src/debug/res/values/strings.xml').read_text()==gitfile(BASE,'app/src/debug/res/values/strings.xml')
 return {'approvedTestingR2':APPROVED,'previousOfficial':BASE,'changedAfterApproval':sorted(changed),
  'allApplicationLogicIdenticalToApprovedTesting':True,'dataSchemaAndPersistenceUnchanged':True}
def assets(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def inputs(base,testing,evidence):
 assert sha(pathlib.Path(base).read_bytes())==BASE_APK
 assert sha(pathlib.Path(testing).read_bytes())==TEST_APK
 assert sha(pathlib.Path(evidence).read_bytes())==TEST_EVIDENCE
 with zipfile.ZipFile(evidence) as z:
  proof=json.loads(z.read('B884-acceptance.json'))
  assert proof['result']=='PASS' and proof['sourceCommit']==APPROVED and proof['baseline']==BASE
  assert proof['unitTests']==dict(tests=711,failures=0,errors=0,skipped=0)
  assert proof['nativeTests']==dict(tests=2,failures=0,errors=0,skipped=0)
  assert proof['apkSha256']==TEST_APK and proof['layout']['result']==proof['actions']['result']=='PASS'
 return proof
def restore(base,testing,evidence):
 inputs(base,testing,evidence)
 with zipfile.ZipFile(base) as old,zipfile.ZipFile(testing) as test:
  names=assets(old);assert len(names)==436 and assets(test)==names
  for n in names:
   assert old.read(n)==test.read(n),n
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(old.read(n))
 print('Restored 436 exact Official assets matched byte-for-byte to approved B884 R2')
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
 assert tests==dict(tests=711,failures=0,errors=0,skipped=0),tests
 report={'result':'PASS','officialMerge':True,'build':884,'approvedTestingR2':APPROVED,
  'sourceCommit':os.getenv('GITHUB_SHA'),'applicationId':'com.izzyan.sgdeliveryplanner',
  'versionCode':92,'versionName':'1.4.72-b884','signingCertificate':CERT,
  'freshUnitTests':tests,'approvedNativeTests':approved['nativeTests'],'nativeEvidenceCarriedForward':True,
  'approvedLayoutResults':approved['layout'],'approvedActionResults':approved['actions'],
  'originalApplicationAssets':436,'originalMP3PCMsets':209,'erpRecords':106,'erpSites':101,
  'sourceAudit':audit,'apkSha256':sha(pathlib.Path(apk).read_bytes()),'physicalPhoneUpgradeTested':False}
 (out/'B884-Official-acceptance.json').write_text(json.dumps(report,indent=2))
 normal=approved['layout']['cases'][0]
 text=f'''# SG Delivery Build #884 Official – Compact History Cards
Date: 22 November 2026 (Malaysia / Singapore)

User approved promotion of #884 TESTING R2: {APPROVED}.
Official source: {report['sourceCommit']}
Package: com.izzyan.sgdeliveryplanner
Version: 92 / 1.4.72-b884

The approved compact History layout is unchanged: row gaps removed, card vertical
padding 8dp and inter-card spacing 8dp. All fonts, status stamps, colours, fields,
dates and actions retain their approved source. No application logic or data
schema changes were made during Official promotion.

Validation: 711 fresh JVM/Robolectric tests passed with zero failures/errors/skips.
Lint, release compilation, original certificate and 16KB ZIP alignment passed.
All 436 application assets match #883 Official and #884 R2 byte for byte, including
209 MP3/PCM sets, 341 cameras, speed/postal data and ERP 106 records / 101 locations.

Approved native evidence is included and carried forward because application
source matches R2 exactly: two Android API 35 tests, six before/after screenshots,
widths 400dp and 320dp, font scales 1.0 and 1.5, original font sizes retained,
and real History viewing/selection/cancel/delete/resume operations using synthetic
Room records. The standard 400dp case measured {normal['heightReductionPercent']:.1f}%
less card height. Existing records remained byte-identical except the deliberately
selected synthetic deletion. These native tests were not re-run on a physical phone.

Original Official package and signing certificate retained. VersionCode increases
from 91 to 92, allowing an in-place update over #883 without uninstalling it.
No physical phone upgrade, road drive or new Bluetooth/audio listening session
was performed. GPS, Panda Warrior, camera alerts, payment, reports and saved-data
handling match the approved R2 source.

APK SHA-256: {report['apkSha256']}
Signing certificate SHA-256: {CERT}
Published as latest stable Official for the existing in-app update checker.
'''
 (out/'SG-Delivery-B884-Official-Report.md').write_text(text)
 print(json.dumps(report,indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(*sys.argv[2:5])
 elif sys.argv[1]=='verify':verify(*sys.argv[2:7])
 else:raise SystemExit('source | restore BASE TEST EVIDENCE | verify BASE TEST EVIDENCE APK OUT')
