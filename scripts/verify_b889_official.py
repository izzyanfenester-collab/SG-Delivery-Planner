#!/usr/bin/env python3
"""Promote the user-approved B889 stamp UI with the unchanged Official identity."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='d4982e678c7a4bfdaaead8570eabd73543fba321'
BASE_APK_SHA='b6053fa4a8e423d6750b16e61e8225c9e2d36c2efa9a224c600b09c05b236d88'
APPROVED_TEST_APK='2e6279d8e3e824f22c65e41eb6326b04be8981128ac6b17effd31d902eb91a7b'
APPROVED_SOURCE_REFERENCE='48001ec3b160a3f9a7979d79216721a2d3f82b8b'
CERT='b70d1c964fb4e59c6bc44aee694e25dd893fa77eb877a4b29646cf33786e3e99'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
ART={'delivered.png':'2f680bae57fe47c203644306cf7f9b62eabda42200f68c26cc84bf8c362c96ff','on_hold.png':'3b24af360501f8962a421edec6aef783dc0c14b250d07c414c7f728fb0781aea','pending.png':'5937206851cfb5c1986103ab2f69127631c23862cafc9e5aebce20ba45c78721'}
def sha(b):return hashlib.sha256(b).hexdigest()
def gitfile(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def outside(s,start,end):
 a=s.index(start);b=s.index(end,a);return (s[:a]+'<approved status rendering>\n'+s[b:]).rstrip()
def source():
 allowed={'app/build.gradle.kts','app/src/debug/res/values/strings.xml',P+'MainActivity.kt',P+'HistoryV6Ui.kt',P+'SingleDeliveryReport.kt',P+'DeliveryStatusStamps.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/DeliveryStatusStamps889Test.kt','app/src/test/java/com/izzyan/sgdeliveryplanner/OrderProofAndReportTest.kt','app/src/test/java/com/izzyan/sgdeliveryplanner/RoadSpeedStore875Test.kt','app/src/test/resources/status-stamps/approved-ui.json','scripts/verify_b889_official.py','.github/workflows/publish-b889-official.yml','docs/BUILD_889_OFFICIAL.md'}
 allowed.update('app/src/main/assets/status_stamps/'+n for n in ART)
 changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT,text=True).splitlines())
 assert changed<=allowed,sorted(changed-allowed)
 for name,start,end in [('MainActivity.kt','@Composable\ninternal fun StopCard(','\n@Composable\nfun Results('),('HistoryV6Ui.kt','@Composable\ninternal fun V6DeliveryCard(','\n/** Both proof images')]:
  old=gitfile(P+name).replace('private fun StopCard(','internal fun StopCard(',1).replace('private fun V6DeliveryCard(','internal fun V6DeliveryCard(',1)
  assert outside(old,start,end)==outside((ROOT/(P+name)).read_text(),start,end),name
 start='        // 1.5x original status font';end='        // Title shares the top row'
 assert outside(gitfile(P+'SingleDeliveryReport.kt'),start,end)==outside((ROOT/(P+'SingleDeliveryReport.kt')).read_text(),start,end)
 expected=gitfile('app/build.gradle.kts').replace('versionCode = 97; versionName = "1.4.77-b888"','versionCode = 98; versionName = "1.4.78-b889"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected
 for n,h in ART.items():assert sha((ROOT/'app/src/main/assets/status_stamps'/n).read_bytes())==h,n
 fixture=json.loads((ROOT/'app/src/test/resources/status-stamps/approved-ui.json').read_text())
 assert fixture['approvedTestingApkSha256']==APPROVED_TEST_APK and len(fixture['comparisons'])==18
 return {'previousOfficial':BASE,'approvedTestingApkSha256':APPROVED_TEST_APK,'approvedTestingSourceReference':APPROVED_SOURCE_REFERENCE,'scopedExistingUiFiles':3,'unrelatedApplicationSourceAndDataSchemaUnchanged':True,'exactPngs':3}
def assets(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def restore(base):
 assert sha(pathlib.Path(base).read_bytes())==BASE_APK_SHA
 with zipfile.ZipFile(base) as z:
  names=assets(z);assert len(names)==436
  for n in names:
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
 print('Restored all 436 exact B888 Official application assets.')
def verify(base,apk,out,signed=False):
 out=pathlib.Path(out);out.mkdir(parents=True,exist_ok=True);audit=source()
 assert sha(pathlib.Path(base).read_bytes())==BASE_APK_SHA
 with zipfile.ZipFile(base) as old,zipfile.ZipFile(apk) as new:
  assert new.testzip() is None
  original=assets(old);assert len(original)==436
  assert assets(new)==original|{'assets/status_stamps/'+n for n in ART}
  assert all(old.read(n)==new.read(n) for n in original)
  for n,h in ART.items():assert sha(new.read('assets/status_stamps/'+n))==h
  assert sum(n.endswith('.mp3') for n in original)==209 and sum(n.endswith('.pcm') for n in original)==209
  abis=sorted({n.split('/')[1] for n in new.namelist() if n.startswith('lib/') and n.endswith('.so')})
  assert 'arm64-v8a' in abis and 'armeabi-v7a' in abis
  dex=b''.join(new.read(n) for n in new.namelist() if n.startswith('classes') and n.endswith('.dex'))
  assert b'DeliveryStatusStampArtwork' in dex and b'status_stamps/' in dex
  assert b'LocalStatusStampReferencePreview' not in dex
 tests={k:0 for k in ['tests','failures','errors','skipped']}
 reports=list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'));assert reports
 for p in reports:
  r=ET.parse(p).getroot()
  for k in tests:tests[k]+=int(r.get(k,0))
 assert tests==dict(tests=735,failures=0,errors=0,skipped=0),tests
 ui=json.loads((ROOT/'app/build/reports/b889-status-stamps/B889-runtime-ui-verification.json').read_text())
 assert ui['result']=='PASS' and ui['defaultLiveRendering'] and len(ui['cases'])==18
 assert all(c['approvedPixelsMatch'] for c in ui['cases'])
 lint=ET.parse(ROOT/'app/build/reports/lint-results-debug.xml').getroot()
 assert not [i for i in lint.findall('issue') if i.get('severity') in ('Error','Fatal')]
 if signed:assert 'Signer #1 certificate SHA-256 digest: '+CERT in (out/'signing-verification.txt').read_text()
 result={'result':'PASS','build':889,'officialMerge':signed,'sourceCommit':os.getenv('GITHUB_SHA'),
  'applicationId':'com.izzyan.sgdeliveryplanner','versionCode':98,'versionName':'1.4.78-b889','sourceAudit':audit,
  'freshUnitTests':tests,'approvedRuntimeUiCasesMatched':18,'originalApplicationAssets':436,'newExactStampAssets':3,
  'originalPandaMp3AndPcmSets':209,'supportedAbis':abis,'lintErrors':0,'signingCertificate':CERT if signed else None,
  'physicalPhoneUpgradeTested':False,'apkSha256':sha(pathlib.Path(apk).read_bytes())}
 name='B889-Official-acceptance.json' if signed else 'B889-candidate-acceptance.json'
 (out/name).write_text(json.dumps(result,indent=2))
 if signed:
  text=f'''# SG Delivery B889 Official

The user explicitly approved merging B889 TESTING into Official.
Official version: 1.4.78-b889 / versionCode 98.
Package: com.izzyan.sgdeliveryplanner.
Previous Official: B888, versionCode 97.
Approved TESTING APK SHA-256: {APPROVED_TEST_APK}.
Official source: {result['sourceCommit']}.

The supplied green DELIVERED, red ON HOLD and orange PENDING PNGs are active on Route cards, History delivery cards and the shared delivery-report renderer. Larger sizes and positions match the approved AFTER UI. Original card/report dimensions, surrounding text, controls, proof areas and COD/PayNow artwork remain unchanged. The exact original PNG bytes, transparency, textures and clockwise tilt are retained.

Validation: 735 fresh tests passed with zero failures, errors or skips. All 18 complete native Android card/report renders match the approved pixel hashes, including 320dp screens, long text and COD/PayNow/no payment layouts. Current-delivery labels and in-place payment changes were checked. Android lint, release compilation, original Official certificate and 16KB ZIP alignment passed.

All 436 original application assets match B888 Official byte for byte, including 209 MP3 and 209 PCM Panda Warrior clips, map, camera and ERP data. Only the three exact stamp assets were added. Unrelated application logic, storage, settings, delivery actions, ordering, GPS and aggregate History statuses are unchanged. Tests for the old status capsule were updated for the new images; the road-speed fixture has deterministic timestamps, without changing road-speed production code.

The original Official package and certificate are retained. The versionCode increases from 97 to 98, supporting an Android update over B888 without uninstalling Official. No storage clearing or data migration is introduced. Physical-device upgrade and driving were not performed during promotion.

APK SHA-256: {result['apkSha256']}.
Signing certificate SHA-256: {CERT}.
Published as the latest stable Official for the existing in-app update checker.
'''
  (out/'SG-Delivery-B889-Official-Report.md').write_text(text)
 print(json.dumps(result,indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 elif sys.argv[1]=='verify':verify(*sys.argv[2:5],signed=len(sys.argv)>5 and sys.argv[5]=='signed')
 else:raise SystemExit('source | restore BASE_APK | verify BASE_APK APK OUTPUT [signed]')
