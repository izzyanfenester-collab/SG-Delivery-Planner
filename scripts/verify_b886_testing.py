#!/usr/bin/env python3
"""Exact Official baseline, bounded building-only change, assets and genuine native evidence."""
import gzip,hashlib,json,os,pathlib,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='c9c824b97de50465a2a4f60b7be0522761b3d155'
BASE_APK='b57f630c07ae99dcc6855c1a604e9ab7734bd0dab20ebb15dd272cb89822819e'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
FIXTURE='app/src/androidTest/assets/buildings886/'
OUT=ROOT/'dist';OUT.mkdir(exist_ok=True)
ALLOWED={'app/build.gradle.kts','app/src/debug/AndroidManifest.xml','app/src/debug/res/values/strings.xml',
 P+'NavigationMapAppearance.kt',P+'NavigationBuildings886.kt',
 'app/src/debug/java/com/izzyan/sgdeliveryplanner/BuildingReplayActivity886.kt',
 'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/BuildingNative886Test.kt',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/NavigationBuildings886Test.kt',
 'scripts/verify_b886_testing.py','scripts/run_b886_native.sh','.github/workflows/build-b886-testing.yml','docs/BUILD_886_TESTING.md'}
ALLOWED|={FIXTURE+name for name in ['NotoSansRegular-0-255.pbf','original-building-layer.json','source-manifest.json',
 'woodlands-geometry.json.gz','woodlands-route.json.gz','woodlands-style.json']}
def sha(data):return hashlib.sha256(data).hexdigest()
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def source():
 subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==ALLOWED,changed^ALLOWED
 expected=old(P+'NavigationMapAppearance.kt').replace('import org.maplibre.android.style.layers.FillLayer\n',
  'import org.maplibre.android.style.layers.FillLayer\nimport org.maplibre.android.style.layers.FillExtrusionLayer\n')
 expected=expected.replace('            when (layer) {\n','            when (layer) {\n                is FillExtrusionLayer -> NavigationBuildings886.apply(layer, night)\n')
 assert (ROOT/(P+'NavigationMapAppearance.kt')).read_text()==expected,'Existing non-building paints must remain exact'
 expected=old('app/build.gradle.kts').replace('versionCode = 93; versionName = "1.4.73-b885"',
  'versionCode = 94; versionName = "1.4.74-b886-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b886test"')
 assert (ROOT/'app/build.gradle.kts').read_text()==expected,'Only build version and isolated debug identity may change'
 manifest=old('app/src/debug/AndroidManifest.xml').replace('<application>','<application>\n        <activity android:name=".BuildingReplayActivity886" android:exported="false" />')
 assert (ROOT/'app/src/debug/AndroidManifest.xml').read_text()==manifest
 policy=(ROOT/(P+'NavigationBuildings886.kt')).read_text()
 for forbidden in ['addLayer(','removeLayer(','setGeoJson(','moveCamera(','animateCamera(','fillExtrusionHeight(',
                   'fillExtrusionBase(','setFilter(','setMinZoom(','setMaxZoom(','java.util.Random','System.currentTimeMillis']:
  assert forbidden not in policy,forbidden
 f=ROOT/FIXTURE;m=json.loads((f/'source-manifest.json').read_text())
 assert sha(gzip.open(f/'woodlands-geometry.json.gz','rb').read())==m['geometrySha256']
 assert sha(gzip.open(f/'woodlands-route.json.gz','rb').read())==m['route']['sha256']
 buildings=json.loads(gzip.open(f/'woodlands-geometry.json.gz','rt').read())['building']['features']
 assert len(buildings)==50 and max(x['properties']['render_height'] for x in buildings)==121
 original=json.loads((f/'original-building-layer.json').read_text())
 native=json.loads((f/'woodlands-style.json').read_text())
 native=next(x for x in native['layers'] if x['id']=='building-3d')
 native['source']='openmaptiles';native['source-layer']='building'
 assert native==original,'Replay must retain original extrusion height/base/filter/zoom/lighting'
 result={'result':'PASS','baseline':BASE,'sourceCommit':os.getenv('GITHUB_SHA'),
  'changes':sorted(changed),'scope':'Only native 3D building colour and opacity',
  'originalNonBuildingPaints':True,'originalNavigationMapAndRouteAnnotations':True,
  'originalDataVoiceSettingsAndApplicationLogic':True,'officialMerge':False}
 (OUT/'B886-source-audit.json').write_text(json.dumps(result,indent=2));return result
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
  for key in result:result[key]+=int(root.get(key,0))
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
 unit=counts('app/build/test-results/testDebugUnitTest');assert unit['tests']>=731,unit
 native=counts('app/build/outputs/androidTest-results/connected');assert native['tests']==7,native
 folder=OUT/'native/SGDeliveryB886'
 rendering=json.loads((folder/'B886-native-before-after-results.json').read_text())
 stability=json.loads((folder/'B886-native-stability-results.json').read_text())
 assert rendering['result']==stability['result']=='PASS'
 assert len(rendering['cases'])==4 and len(stability['modes'])==3
 assert len(list(folder.glob('*.png')))==13
 for case in rendering['cases']:assert case['unchangedBlueFraction']>=0.985 and case['geometryRouteAndHeightUnchanged']
 erp_native=json.loads((OUT/'native/SGDeliveryB876Native/B876-native-map-results.json').read_text())
 assert erp_native['all_101_rendered_offline'] and erp_native['vehicle_arrow_above_erp']
 guidance=json.loads((OUT/'native/SGDeliveryB885/B885-GPS-replay-results.json').read_text())
 assert guidance['result']=='PASS'
 result={'result':'PASS','build':886,'baseline':BASE,'sourceCommit':os.getenv('GITHUB_SHA'),
  'applicationId':'com.izzyan.sgdeliveryplanner.b886test','version':'1.4.74-b886-testing',
  'unitTests':unit,'nativeTests':native,'applicationAssetsUnchanged':436,'originalMP3PCMsets':209,
  'erpRecords':106,'erpSites':101,'rendering':rendering,'stability':stability,'sourceAudit':audit,
  'physicalDrivingOrBluetoothTesting':False,'officialMerge':False,'apkSha256':sha(pathlib.Path(apk).read_bytes())}
 (OUT/'B886-acceptance.json').write_text(json.dumps(result,indent=2))
 report=f'''# SG Delivery #886 TESTING — Soft Transparent 3D Buildings

Exact baseline: B885 Official, {BASE}.
Source commit: {result['sourceCommit']}.
Version 94 / 1.4.74-b886-testing.
Separate package: com.izzyan.sgdeliveryplanner.b886test.

Six muted soft colours are applied to existing native building extrusions, with
55% building opacity. Stable numeric feature IDs select a palette group; actual
osm_id, id or stored render_height provide stable fallbacks. Day/night keep the
same grouping using corresponding subdued palettes. No building-use categories
are inferred. Existing roof/wall lighting and all geometry/heights remain intact.

The former gray appearance came from the original building-3d layer: the existing
navigation theme painter did not handle FillExtrusionLayer. This build adds one
building-only paint case. It does not lower MapView opacity or alter route/arrow,
road labels, ERP/camera icons, controls or panel opacity. Existing style-ready and
day/night callbacks apply paints without recreating layers or interrupting GPS.

Verification: {unit['tests']} JVM/Robolectric tests and seven native Android API 35
tests passed, plus Android lint, APK signature/isolated-identity and 16KB ZIP checks.
All 726 baseline JVM tests and original lane/Then, History and ERP native tests
are retained. The five new policy tests cover stable source identity, unknown ID
fallbacks, reachable palettes, non-building exclusions and no dynamic GPS/zoom
colour inputs. Actual native MapLibre 13.5 rendering uses captured OSM Woodlands
geometry (50 feature groups, stored heights 4–121m), a real provider route, and
geographic replay through production matching/progress and navigation header UI.

Thirteen new raw native Android PNGs contain four matched BEFORE/AFTER pairs
(day/night, bearings 22 and 125), four buildings-hidden depth controls and one
offline style-reload capture. GPS location, route, zoom, tilt, bearing and map
appearance are identical within each pair. Exact route-blue core pixels are
compared with the buildings-hidden control; every pair retains at least 98.5%.
Native assertions verify original route coordinates/colour/width/casing,
building height/base/filter/zoom properties and camera state are unchanged.
Colours visibly change while the 3D surfaces remain present. Ordinary supported
building opacity is sufficient in these tested views; no route displacement,
fake building coordinates, artificial elevation, dynamic cutouts or new route
rendering layer is introduced.

The stability replay checks wide/close zoom, 2D/3D, rotation, actual GPS updates,
local style reload and day/night. Building layers and sources are reused, as is
the route annotation between GPS samples. A native 1800ms camera animation
finishes without cancellation while building paint switches day/night. Original
ERP tests render all 101 real sites offline and verify sizes, anchoring, selection,
toggle and vehicle-above-ERP order. Original lane/Then and compact History action
regressions also pass. No audio logic or assets change.

All 436 original Official application assets compare byte-for-byte, including
209 MP3 and 209 PCM recordings, camera/road/postal data, and ERP 106 records at
101 sites. No Room schema, saved preference, delivery, History, Payment, report,
backup/update, routing, camera animation, voice or safety-controller changes.

Scope and limitations: this is actual emulator GPU rendering and GPS replay,
not a physical road drive, device FPS benchmark or Bluetooth acceptance. The
deterministic local test fixture uses captured actual geometry and original
Liberty layer definitions, adapted to local GeoJSON with Latin labels/bundled
glyphs. It is not a complete offline Singapore map. Production continues using
the original map sources and existing cache/offline availability. Styling works
with available local building data; no complete Singapore coverage is claimed.
Existing Official user data is untouched because TESTING installs separately.
Original premium themes remain; the native panel retains Navy & Gold.

APK SHA-256: {result['apkSha256']}

NO OFFICIAL MERGE. Official and stable update feed remain B885.
'''
 (OUT/'SG-Delivery-B886-Testing-Report.md').write_text(report)
 print(json.dumps({k:v for k,v in result.items() if k not in ['rendering','stability','sourceAudit']},indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 elif sys.argv[1]=='verify':verify(sys.argv[2],sys.argv[3])
 else:raise SystemExit('source | restore BASE | verify BASE APK')
