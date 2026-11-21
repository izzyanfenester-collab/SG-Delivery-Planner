#!/usr/bin/env python3
"""Exact #883 Official baseline, four History-only layout edits, native evidence."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='680cac0d016b4002aab2c148f16694a8a223035f'
BASE_APK='f862a1d24cc6c6257089939437c92246a89a37033fb8a48c01e7ddd85ab1e7fd'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
OUT=ROOT/'dist';OUT.mkdir(exist_ok=True)
CHANGED={'app/build.gradle.kts','app/src/debug/AndroidManifest.xml','app/src/debug/res/values/strings.xml',
 P+'MainActivity.kt','app/src/debug/java/com/izzyan/sgdeliveryplanner/BaselineHistory883.kt',
 'app/src/debug/java/com/izzyan/sgdeliveryplanner/HistorySpacingVerificationActivity884.kt',
 'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/HistorySpacing884Test.kt',
 'scripts/verify_b884_testing.py','scripts/run_b884_native.sh','.github/workflows/build-b884-testing.yml'}
def sha(data):return hashlib.sha256(data).hexdigest()
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def history(code):
 start=code.index('@OptIn(ExperimentalFoundationApi::class)\n@Composable\nfun History(')
 end=code.index('/** Small numbered red stop markers',start)
 return start,end,code[start:end]
def source():
 subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==CHANGED,changed^CHANGED
 base=old(P+'MainActivity.kt');a,b,h=history(base);expected=h
 replacements=[('verticalArrangement = Arrangement.spacedBy(16.dp)','verticalArrangement = Arrangement.spacedBy(8.dp)'),
 ('Column(Modifier.padding(SGDimensions.Normal), verticalArrangement = Arrangement.spacedBy(12.dp))','Column(Modifier.padding(horizontal = SGDimensions.Normal, vertical = 8.dp))'),
 ('Modifier.fillMaxWidth().heightIn(min = 76.dp)','Modifier.fillMaxWidth()'),
 ('verticalArrangement = Arrangement.spacedBy(3.dp)','verticalArrangement = Arrangement.Top')]
 for x,y in replacements:
  assert expected.count(x)==1;expected=expected.replace(x,y)
 assert (ROOT/(P+'MainActivity.kt')).read_text()==base[:a]+expected+base[b:]
 ref=(ROOT/'app/src/debug/java/com/izzyan/sgdeliveryplanner/BaselineHistory883.kt').read_text()
 exact=h.replace('fun History(','internal fun BaselineHistory883(').replace('PageTitle(','BaselinePageTitle883(').replace('DetailRow(','BaselineDetailRow883(')
 assert exact in ref,'Native comparison must use the exact original History composable'
 original=old('app/build.gradle.kts')
 expected=original.replace('versionCode = 91; versionName = "1.4.71-b883"','versionCode = 92; versionName = "1.4.72-b884-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b884test"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected
 result={'result':'PASS','baseline':BASE,'changes':sorted(changed),'historyChangedStatements':4,
  'fontSizesWeightsLineHeightsUnchanged':True,'originalStatusArtSizeRotationUnchanged':True,
  'allClickHandlersAndHistoryDataLogicUnchanged':True,'allOtherApplicationSourceUnchanged':True,'officialMerge':False}
 (OUT/'B884-source-audit.json').write_text(json.dumps(result,indent=2))
 return result
def asset_names(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def restore(path):
 assert sha(pathlib.Path(path).read_bytes())==BASE_APK
 with zipfile.ZipFile(path) as z:
  names=asset_names(z);assert len(names)==436
  for n in names:
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
 print('436 exact Official application assets restored; generated ART profiles excluded')
def counts(folder):
 result=dict(tests=0,failures=0,errors=0,skipped=0)
 files=list((ROOT/folder).rglob('TEST-*.xml'));assert files,folder
 for p in files:
  tree=ET.parse(p).getroot()
  for key in result:result[key]+=int(tree.get(key,0))
 assert all(result[k]==0 for k in ('failures','errors','skipped')),result
 return result
def verify(base,apk):
 audit=source();assert sha(pathlib.Path(base).read_bytes())==BASE_APK
 with zipfile.ZipFile(base) as old_zip,zipfile.ZipFile(apk) as new_zip:
  assert new_zip.testzip() is None
  names=asset_names(old_zip);assert len(names)==436 and names==asset_names(new_zip)
  assert all(old_zip.read(n)==new_zip.read(n) for n in names)
  assert sum(n.endswith('.mp3') for n in names)==209
  assert sum(n.endswith('.pcm') for n in names)==209
  erp=json.loads(new_zip.read('assets/sg_erp_manifest_876.json'))
  assert erp['record_count']==106 and erp['location_count']==101
 unit=counts('app/build/test-results/testDebugUnitTest');assert unit['tests']==711,unit
 native=counts('app/build/outputs/androidTest-results/connected');assert native['tests']==2,native
 folder=OUT/'native/SGDeliveryB884'
 layout=json.loads((folder/'B884-layout-results.json').read_text())
 actions=json.loads((folder/'B884-actions-results.json').read_text())
 assert layout['result']==actions['result']=='PASS'
 assert len(layout['cases'])==3
 for case in layout['cases']:
  assert case['fontSizesUnchanged'] and case['noTextOverflow'] and case['heightReductionPercent']>0
 assert 25<=layout['cases'][0]['heightReductionPercent']<=35
 assert len(list(folder.glob('*.png')))==6
 result={'result':'PASS','build':884,'sourceCommit':os.getenv('GITHUB_SHA'),'baseline':BASE,
  'applicationId':'com.izzyan.sgdeliveryplanner.b884test','version':'1.4.72-b884-testing',
  'unitTests':unit,'nativeTests':native,'unchangedApplicationAssets':436,'originalMP3PCMsets':209,
  'erpRecords':106,'erpLocations':101,'layout':layout,'actions':actions,'sourceAudit':audit,
  'physicalPhoneTesting':False,'officialMerge':False,'apkSha256':sha(pathlib.Path(apk).read_bytes())}
 (OUT/'B884-acceptance.json').write_text(json.dumps(result,indent=2))
 normal=layout['cases'][0]
 report=f'''# SG Delivery #884 TESTING – Compact History Cards (Revision 2)

Baseline: #883 Official, {BASE}.
Source: {result['sourceCommit']}
Separate package: com.izzyan.sgdeliveryplanner.b884test
Version: 92 / 1.4.72-b884-testing

Only History spacing and natural sizing changed: inter-card gap 16 to 8dp,
card vertical padding 16 to 8dp (horizontal padding unchanged), row gaps 12 to 0dp,
and date/status/ID gaps 3 to 0dp. The redundant 76dp header minimum is removed;
the unchanged 76dp stamp still determines the natural minimum content height.
Font sizes, weights, line heights, status artwork/rotation, card colours,
transparency, backdrop, labels, values, dates and every click handler are unchanged.

Native Android API 35 comparison uses the actual production History composable
and an exact DEBUG-only copy of #883, with identical synthetic Room records,
viewport, density, fonts and background. Revision 2 matches the white inherited
content colour from production App Scaffold in the standalone comparison host.
At width 400dp/font scale 1.0 the first
card changes from {normal['baselineHeightDp']:.1f}dp to {normal['compactHeightDp']:.1f}dp,
saving {normal['heightReductionPercent']:.1f}%. Further captures cover width 320dp
at font scales 1.0 and 1.5. Typography is identical; native text checks retain all
characters and line bounds with a one-pixel rendering-rounding tolerance. Raw
layout diagnostics are retained. All three status stamps remain present. Six original
before/after PNGs and exact measured results are included.

Real History UI actions verified: completed-route read-only viewing, long-press
selection, cancelling deletion, resuming the exact selected unfinished route,
and deleting only the selected synthetic record. Other records remain byte-identical.
No actual user records are included in these fixtures or modified in Official.

711 original JVM/Robolectric regressions and two native Android tests passed.
Lint, APK signing, package identity and 16KB ZIP alignment passed. All 436
application assets are byte-identical to #883 Official, including 209 MP3/PCM sets,
camera/road/postal data, ERP 106 records / 101 sites. All other application source,
including GPS, Panda Warrior, data persistence, payment and reports is unchanged.
The baseline exposes its existing Navy & Gold palette; no theme code is altered.

These are emulator layout/touch/persistence checks. No physical phone session,
road drive or new Bluetooth/voice test was performed for this History-only change.
Official main and latest stable release remain #883. NO OFFICIAL MERGE.

APK SHA-256: {result['apkSha256']}
'''
 (OUT/'SG-Delivery-B884-Testing-Report.md').write_text(report)
 print(json.dumps(result,indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 elif sys.argv[1]=='verify':verify(sys.argv[2],sys.argv[3])
 else:raise SystemExit('source | restore BASE | verify BASE APK')
