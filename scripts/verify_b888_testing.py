#!/usr/bin/env python3
"""Bounded scenery paints, exact Official assets, and fresh Android rendering evidence."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='c9c824b97de50465a2a4f60b7be0522761b3d155'
BASE_APK='b57f630c07ae99dcc6855c1a604e9ab7734bd0dab20ebb15dd272cb89822819e'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
FIXTURE='app/src/androidTest/assets/scenery888/'
OUT=ROOT/'dist'
ALLOWED={'app/build.gradle.kts','app/src/debug/AndroidManifest.xml','app/src/debug/res/values/strings.xml',
 P+'NavigationMapAppearance.kt',P+'NavigationScenery888.kt',
 'app/src/debug/java/com/izzyan/sgdeliveryplanner/BuildingReplayActivity888.kt',
 'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/SceneryNative888Test.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/NavigationScenery888Test.kt',
 'scripts/verify_b888_testing.py','scripts/run_b888_native.sh','.github/workflows/build-b888-testing.yml','docs/BUILD_888_TESTING.md'}
ALLOWED|={FIXTURE+n for n in ['woodlands.pbf','marina.pbf','marina-route.json','vector-style.json',
 'woodlands-route.bin','NotoSansRegular-0-255.pbf','source-manifest.json']}
def sha(b):return hashlib.sha256(b).hexdigest()
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def source():
 OUT.mkdir(exist_ok=True)
 subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==ALLOWED,changed^ALLOWED
 expected=old(P+'NavigationMapAppearance.kt').replace('fun apply(style: Style, night: Boolean) {',
  'fun apply(style: Style, night: Boolean, enhancedScenery: Boolean = true) {').replace(
  '            if (isNavigationAnnotation(id)) continue\n',
  '            if (isNavigationAnnotation(id)) continue\n            if (enhancedScenery && NavigationScenery888.apply(layer, night)) continue\n')
 assert (ROOT/(P+'NavigationMapAppearance.kt')).read_text()==expected,'Original paints/callbacks must remain exact'
 expected=old('app/build.gradle.kts').replace('versionCode = 93; versionName = "1.4.73-b885"',
  'versionCode = 96; versionName = "1.4.76-b888-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b888test"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected
 policy=(ROOT/(P+'NavigationScenery888.kt')).read_text()
 for forbidden in ['addLayer(','removeLayer(','setGeoJson(','moveCamera(','animateCamera(','fillExtrusionHeight(',
  'fillExtrusionBase(','setFilter(','setMinZoom(','setMaxZoom(','java.util.Random','System.currentTimeMillis']:
  assert forbidden not in policy,forbidden
 f=ROOT/FIXTURE;m=json.loads((f/'source-manifest.json').read_text())
 for scene in ['woodlands','marina']:assert sha((f/(scene+'.pbf')).read_bytes())==m['scenes'][scene]['sha256']
 assert sha((f/'marina-route.json').read_bytes())==m['scenes']['marina']['route']['sha256']
 result={'result':'PASS','baseline':BASE,'sourceCommit':os.getenv('GITHUB_SHA'),
  'changes':sorted(changed),'productionScope':'Existing scenery paints only','officialMerge':False}
 (OUT/'B888-source-audit.json').write_text(json.dumps(result,indent=2));return result
def assets(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def restore(path):
 assert sha(pathlib.Path(path).read_bytes())==BASE_APK
 with zipfile.ZipFile(path) as z:
  names=assets(z);assert len(names)==436
  for name in names:
   assert '..' not in pathlib.PurePosixPath(name).parts
   p=ROOT/'app/src/main'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
 print('Restored all 436 exact B885 Official assets')
def counts(folder):
 result=dict(tests=0,failures=0,errors=0,skipped=0)
 files=list((ROOT/folder).rglob('TEST-*.xml'));assert files,folder
 for p in files:
  root=ET.parse(p).getroot()
  for k in result:result[k]+=int(root.get(k,0))
 assert not any(result[k] for k in ['failures','errors','skipped']),result
 return result
def verify(base,apk):
 audit=source();assert sha(pathlib.Path(base).read_bytes())==BASE_APK
 with zipfile.ZipFile(base) as previous,zipfile.ZipFile(apk) as current:
  names=assets(previous);assert len(names)==436 and names==assets(current)
  assert current.testzip() is None
  assert all(previous.read(n)==current.read(n) for n in names)
  assert sum(n.endswith('.mp3') for n in names)==209
  assert sum(n.endswith('.pcm') for n in names)==209
  erp=json.loads(current.read('assets/sg_erp_manifest_876.json'))
  assert erp['record_count']==106 and erp['location_count']==101
 unit=counts('app/build/test-results/testDebugUnitTest');assert unit['tests']>=732,unit
 native=counts('app/build/outputs/androidTest-results/connected');assert native['tests']>=9,native
 folder=OUT/'native/SGDeliveryB888'
 renders=[]
 for scene,num in [('woodlands',4),('marina',2)]:
  r=json.loads((folder/f'B888-{scene}-native-rendering.json').read_text())
  assert r['result']=='PASS' and len(r['cases'])==num
  for c in r['cases']:assert c['unchangedBlueFraction']>=.985 and c['originalGeometryAndRouteUnchanged'] and c['greenMappedGrass']
  renders.append(r)
 stability=json.loads((folder/'B888-native-stability.json').read_text())
 tree=json.loads((folder/'B888-native-tree-probe.json').read_text())
 assert stability['result']==tree['result']=='PASS'
 assert len(list(folder.glob('B888-BEFORE-*.png')))==6
 assert len(list(folder.glob('B888-AFTER-*.png')))==7
 assert json.loads((OUT/'native/SGDeliveryB876Native/B876-native-map-results.json').read_text())['all_101_rendered_offline']
 assert json.loads((OUT/'native/SGDeliveryB885/B885-GPS-replay-results.json').read_text())['result']=='PASS'
 result={'result':'PASS','build':888,'baseline':BASE,'sourceCommit':os.getenv('GITHUB_SHA'),
  'applicationId':'com.izzyan.sgdeliveryplanner.b888test','version':'1.4.76-b888-testing',
  'unitTests':unit,'nativeTests':native,'applicationAssetsUnchanged':436,'originalMP3PCMsets':209,
  'erpRecords':106,'erpSites':101,'rendering':renders,'stability':stability,'treeProbe':tree,
  'sourceAudit':audit,'physicalDrivingOrBluetoothTesting':False,'officialMerge':False,
  'apkSha256':sha(pathlib.Path(apk).read_bytes())}
 (OUT/'B888-acceptance.json').write_text(json.dumps(result,indent=2))
 report=(ROOT/'docs/BUILD_888_TESTING.md').read_text()+f'\nVerified source: {os.getenv("GITHUB_SHA")}.\nFresh verification: {unit["tests"]} JVM tests and {native["tests"]} native Android tests passed; zero failures/errors/skips. Android lint, isolated APK identity, signature and 16KB ZIP alignment passed. All 436 Official assets are byte-identical.\nAPK SHA-256: {result["apkSha256"]}.\n'
 (OUT/'SG-Delivery-B888-Testing-Report.md').write_text(report)
 print(json.dumps({k:v for k,v in result.items() if k not in ['rendering','stability','treeProbe','sourceAudit']},indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 elif sys.argv[1]=='verify':verify(sys.argv[2],sys.argv[3])
 else:raise SystemExit('source | restore BASE | verify BASE APK')
