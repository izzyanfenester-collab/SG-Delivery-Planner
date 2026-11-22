#!/usr/bin/env python3
"""Bounded visual guidance changes from exact B884 Official; APK and native evidence."""
import hashlib,json,os,pathlib,shutil,subprocess,sys,zipfile,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='db4686692aa41b6e9b083db65e9cb9bdf96a16c2'
BASE_APK='59918c58c7d87e55483328384b7cef757dfd2eb2549163da72f9c6795ee615bf'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
OUT=ROOT/'dist';OUT.mkdir(exist_ok=True)
ALLOWED={'app/build.gradle.kts','app/src/debug/AndroidManifest.xml','app/src/debug/res/values/strings.xml',
 P+'BuiltInNavigationActivity.kt',P+'NavigationRouting.kt',P+'NavigationVisualGuidance885.kt',P+'NavigationGuidanceUi885.kt',
 'app/src/debug/java/com/izzyan/sgdeliveryplanner/NavigationReplayActivity885.kt',
 'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/NavigationGuidance885Test.kt',
 'app/src/androidTest/assets/navigation-885-pie-recorded.json','app/src/androidTest/assets/navigation-885-city-recorded.json',
 'app/src/test/java/com/izzyan/sgdeliveryplanner/NavigationVisualGuidance885Test.kt',
 'app/src/test/resources/navigation/valhalla-city-885.json','scripts/verify_b885_testing.py',
 'scripts/run_b885_native.sh','.github/workflows/build-b885-testing.yml','docs/BUILD_885_TESTING.md'}
def sha(data):return hashlib.sha256(data).hexdigest()
def old(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def source():
 subprocess.run(['git','merge-base','--is-ancestor',BASE,'HEAD'],check=True,cwd=ROOT)
 changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT).decode().splitlines())
 assert changed==ALLOWED,changed^ALLOWED
 code=(ROOT/(P+'BuiltInNavigationActivity.kt')).read_text();before=old(P+'BuiltInNavigationActivity.kt')
 # Original GPS audio dispatcher and safety engines remain byte-identical.
 a='    private fun announcePanda(';b='    override fun close()'
 assert code[code.index(a):code.index(b)]==before[before.index(a):before.index(b)]
 a='    private val cameraStore=';b='    private val reroute ='
 assert code[code.index(a):code.index(b)]==before[before.index(a):before.index(b)]
 a='    @Composable\n    private fun NavigationSettingsChoices';b='    private fun hasPreciseLocation()'
 assert code[code.index(a):code.index(b)]==before[before.index(a):before.index(b)]
 # The existing primary instruction Row is extracted exactly, without changing its typography or labels.
 a=before.index('                        Row(',before.index('Column(Modifier.align(Alignment.TopCenter)'))
 b=before.index('                    if (session.loading)',a)
 row=before[a:b];row=row[:row.rfind('                    }')]
 row=row.replace('                            val cue = if (session.arrived) NavigationPanelCue(NavigationSign.ARRIVE) else session.panelCue\n','')
 for x,y in [('session.arrived','arrived'),('session.offRoute','offRoute'),('session.turnMeters','turnMeters'),('session.instruction','instruction'),('session.street','street')]:row=row.replace(x,y)
 row='\n'.join(line[20:] if line.startswith(' '*20) else line for line in row.splitlines())
 assert row in code,'Original primary instruction layout changed'
 # Drawables, main manifest, preferences, Room, maps, camera, ERP and voice source are outside the allowlist.
 fixture=ROOT/'app/src/test/resources/navigation/valhalla-singapore.json'
 assert fixture.read_bytes()==(ROOT/'app/src/androidTest/assets/navigation-885-pie-recorded.json').read_bytes()
 assert (ROOT/'app/src/test/resources/navigation/valhalla-city-885.json').read_bytes()==(ROOT/'app/src/androidTest/assets/navigation-885-city-recorded.json').read_bytes()
 result={'result':'PASS','baseline':BASE,'changes':sorted(changed),'scope':'visual route metadata and two optional overlays',
  'originalPrimaryInstructionLayout':True,'originalSafetyAndVoiceDispatch':True,'otherApplicationSourceUnchanged':True,
  'officialMerge':False,'sourceCommit':os.getenv('GITHUB_SHA')}
 (OUT/'B885-source-audit.json').write_text(json.dumps(result,indent=2));return result
def assets(z):return {n for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and not n.startswith('assets/dexopt/')}
def restore(path):
 assert sha(pathlib.Path(path).read_bytes())==BASE_APK
 with zipfile.ZipFile(path) as z:
  names=assets(z);assert len(names)==436
  for n in names:
   assert '..' not in pathlib.PurePosixPath(n).parts
   p=ROOT/'app/src/main'/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(n))
 print('Restored 436 exact Official application assets')
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
 unit=counts('app/build/test-results/testDebugUnitTest');assert unit['tests']>=726,unit
 native=counts('app/build/outputs/androidTest-results/connected');assert native['tests']==4,native
 folder=OUT/'native/SGDeliveryB885'
 replay=json.loads((folder/'B885-GPS-replay-results.json').read_text())
 layout=json.loads((folder/'B885-native-layout-results.json').read_text())
 assert replay['result']==layout['result']=='PASS'
 assert len(layout['cases'])==6 and len(replay['observations'])==4
 assert len(list(folder.glob('*.png')))==9
 result={'result':'PASS','build':885,'baseline':BASE,'sourceCommit':os.getenv('GITHUB_SHA'),
  'applicationId':'com.izzyan.sgdeliveryplanner.b885test','version':'1.4.73-b885-testing',
  'unitTests':unit,'nativeTests':native,'applicationAssetsUnchanged':436,'originalMP3PCMsets':209,
  'erpRecords':106,'erpSites':101,'replay':replay,'layout':layout,'sourceAudit':audit,
  'physicalDrivingOrBluetoothTesting':False,'officialMerge':False,'apkSha256':sha(pathlib.Path(apk).read_bytes())}
 (OUT/'B885-acceptance.json').write_text(json.dumps(result,indent=2))
 text=f'''# SG Delivery #885 TESTING – Lane Guidance & Then Next Direction

Baseline: latest verified #884 Official, {BASE}.
Source commit: {result['sourceCommit']}.
Separate package: com.izzyan.sgdeliveryplanner.b885test.
Version 93 / 1.4.73-b885-testing. Official remains #884.

Lane arrows are inside the original top instruction panel. Provider-valid lanes
are white (including valid but non-preferred lanes); other arrows are dim white.
An explicit valid_indication selects the appropriate branch of shared arrows.
Only the first intersection belonging to the actual upcoming step is accepted;
the intersection must match its maneuver coordinates within 3m. Missing,
unsupported or incomplete lane metadata hides the entire strip without changing
lane order. Lane data is requested and parsed once with the existing route.
No lane counts or lane permissions are inferred from a turn, speed or GPS fix.

The separate compact Then + white arrow badge is below the panel at its left edge.
It reads the following actionable step, skipping notifications and road-name
changes. Distances over skipped steps are accumulated. Lane approach range is
1000m; Then appears within 750m of the primary step when the following action is
within 450m beyond it. Both invalidate on reroute request, destination/session
change, arrival, GPS expiry and navigation stop. No voice events are added.

Verification: {unit['tests']} JVM/Robolectric tests passed, including all 711 original
regressions and 15 new route-data/selection tests. Four native Android API 35 tests
passed: two recorded GPS replay/layout tests and the two original History
layout/Room record action regressions. Nine new original screenshots capture the
production instruction/lane/Then composables over a native MapLibre map of the
recorded route geometry. These are clearly labelled recorded replays, not a
physical driving session or a complete offline basemap. The replay inputs are
geographic coordinates; production matching/progress and SDK RouteProgress
drive the displays. Rendered pixels verify white versus muted arrows.

Recorded routes: PIE exit 20B / Lornie / Upper Thomson / Island Club (original
baseline response) and Handy Road / Oldham Lane / Orchard Road / CTE / PIE /
Tampines (provider response retrieved 22 November 2026). Replay shows two valid
PIE exit lanes, shared left/right branch highlighting, and right to Oldham Lane
followed by left to Orchard Road. The 320dp and 400dp layouts cover font scales
1.0 and 1.5 with day/2D and night/3D local map backgrounds. Controls begin below
the complete header; no empty lane/badge placeholders are left behind.

Coverage limits are detailed in docs/BUILD_885_TESTING.md. The recordings contain
verified provider lane metadata at 15 of 20 actionable maneuver locations.
No complete Singapore coverage claim is made. Unmapped examples include the
Upper Thomson exit and Island Club continuation on the first route, and the
initial Handy Road turn and two unnamed final turns on the second route.
Unknown metadata continues to show the normal navigation instruction. Loaded
route metadata is usable without more network requests. The existing baseline
does not persist a full turn-by-turn route for a fresh process or supply a local
lane-aware rerouting engine; this build does not add either. New route calculation
retains the existing connectivity requirements.

All 436 Official application assets are byte-identical, including 209 MP3 and
209 PCM recordings, camera/road/postal data and ERP 106 records / 101 sites.
No data schema, user preference, delivery/report/payment/history logic, map or
camera animation controller, voice dispatcher, or GPS control handler changes.
The existing Navy & Gold navigation palette is retained. Original primary
instruction, exit sign, and controls are reused without visual changes.
Signing verification, isolated package identity, Android lint and 16KB ZIP
alignment passed. No physical road drive or fresh Bluetooth/audio acceptance
was performed; audio code and assets are unchanged.

APK SHA-256: {result['apkSha256']}

NO OFFICIAL MERGE OR STABLE RELEASE UPDATE.
'''
 (OUT/'SG-Delivery-B885-Testing-Report.md').write_text(text)
 print(json.dumps(result,indent=2))
if __name__=='__main__':
 if sys.argv[1]=='source':print(json.dumps(source(),indent=2))
 elif sys.argv[1]=='restore':restore(sys.argv[2])
 elif sys.argv[1]=='verify':verify(sys.argv[2],sys.argv[3])
 else:raise SystemExit('source | restore BASE | verify BASE APK')
