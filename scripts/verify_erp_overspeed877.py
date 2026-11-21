#!/usr/bin/env python3
"""Bounded ERP/overspeed TESTING acceptance on exact B877 Official."""
import hashlib,json,pathlib,re,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
BASE='850dfe1f48b8e8f42ef607865ffd8e1ac6338ef3'
BASE_SHA='b484fd62efef51f294ae6dbac472f2cb0ddf629ba8795f85c20a3c7ba924f5e5'
P='app/src/main/java/com/izzyan/sgdeliveryplanner/'
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'dist'
OUT.mkdir(exist_ok=True)
PROFILES={'assets/dexopt/baseline.prof','assets/dexopt/baseline.profm'}
def sha(data):return hashlib.sha256(data).hexdigest()
def original(path):return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def source():
    allowed={
      'app/build.gradle.kts','app/src/debug/AndroidManifest.xml','app/src/debug/res/values/strings.xml',
      P+'BuiltInNavigationActivity.kt',P+'CameraSafetySettingsUi.kt',P+'ErpMapLayer876.kt',P+'NavigationMap.kt',
      P+'OverspeedWarning877.kt',P+'OverspeedNavigation877.kt',P+'OverspeedSettings877.kt',
      'app/src/debug/java/com/izzyan/sgdeliveryplanner/ErpOverspeedVerificationActivity877.kt',
      'app/src/test/java/com/izzyan/sgdeliveryplanner/OverspeedWarning877Test.kt',
      'app/src/test/java/com/izzyan/sgdeliveryplanner/OverspeedPreferences877Test.kt',
      'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/ErpOverspeedEvidence877.kt',
      'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/ErpLayerOrder877Test.kt',
      'app/src/androidTest/java/com/izzyan/sgdeliveryplanner/OverspeedSession877Test.kt',
      'app/src/androidTest/assets/valhalla-singapore-877-fixture.json',
      'scripts/run_erp_overspeed877.sh','scripts/verify_erp_overspeed877.py',
      '.github/workflows/build-877-erp-overspeed-testing.yml'}
    changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD'],cwd=ROOT).decode().splitlines())
    assert changed==allowed,changed^allowed
    assert (ROOT/(P+'NavigationMap.kt')).read_text()==original(P+'NavigationMap.kt').replace(
        'ErpMapIntegration876(map,styleReady,erp)','ErpMapIntegration876(map,styleReady,erp,enforcementStyleVersion)')
    before=original(P+'ErpMapLayer876.kt').split('/** One native GeoJSON')[0]
    assert (ROOT/(P+'ErpMapLayer876.kt')).read_text().startswith(before),'Original ERP image renderer changed'
    gradle=original('app/build.gradle.kts').replace('versionCode = 85; versionName = "1.4.65-b877"',
        'versionCode = 86; versionName = "1.4.65-b877-erp-overspeed-testing"').replace('applicationIdSuffix = ".testing"','applicationIdSuffix = ".b877erptest"')
    gradle=gradle.replace(' androidTestImplementation("androidx.test.ext:junit:1.3.0")',' androidTestImplementation("androidx.test.ext:junit:1.3.0")\n androidTestImplementation("androidx.test:rules:1.7.0")')
    assert (ROOT/'app/build.gradle.kts').read_text()==gradle
    for name in ('NavigationVoiceAudio.kt','PandaVoiceMapper.kt','RoadSpeedLimit875.kt','RoadSpeedStore875.kt',
      'CameraVoicePlanner865.kt','CameraSafetyCore.kt','NavigationRouting.kt','NavigationCameraDirector.kt','PlannerViewModel.kt','MainActivity.kt'):
        assert (ROOT/(P+name)).read_text()==original(P+name),name
    for name in ('OverspeedNavigation877.kt','OverspeedSettings877.kt','OverspeedWarning877.kt'):
        code=(ROOT/(P+name)).read_text()
        assert 'TextToSpeech' not in code and 'MediaPlayer(' not in code
    audit={'result':'PASS','baseline':BASE,'changedFiles':sorted(changed),'officialMerge':False,
      'original209AudioEngineUnchanged':True,'roadCatalogAndMatcherUnchanged':True,
      'routeGeometryAndCameraAnimationUnchanged':True,'erpArtworkAndCoordinatesUnchanged':True,
      'deliverySilenceAndUserPersistenceUnchanged':True}
    (OUT/'source-audit.json').write_text(json.dumps(audit,indent=2))
    print(json.dumps(audit))
def restore(path):
    assert sha(pathlib.Path(path).read_bytes())==BASE_SHA
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.startswith('assets/') or name.endswith('/') or name in PROFILES:continue
            assert '..' not in pathlib.PurePosixPath(name).parts
            target=ROOT/'app/src/main'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
    print('436 original application assets restored; generated ART profiles excluded')
def counts(folder):
    total=dict(tests=0,failures=0,errors=0,skipped=0)
    files=list(pathlib.Path(folder).rglob('TEST-*.xml'));assert files,folder
    for file in files:
        root=ET.parse(file).getroot()
        for key in total:total[key]+=int(root.get(key,0))
    assert not any(total[k] for k in ('failures','errors','skipped')),total
    return total
def verify(base,apk):
    source()
    assert sha(pathlib.Path(base).read_bytes())==BASE_SHA
    def assets(z):return {n:sha(z.read(n)) for n in z.namelist() if n.startswith('assets/') and not n.endswith('/') and n not in PROFILES}
    with zipfile.ZipFile(base) as old,zipfile.ZipFile(apk) as new:
        assert new.testzip() is None
        assert assets(old)==assets(new) and len(assets(new))==436
        assert sum(n.endswith('.mp3') for n in assets(new))==209
        assert sum(n.endswith('.pcm') for n in assets(new))==209
        manifest=json.loads(new.read('assets/voicepacks/panda_warrior_camera/manifest.json'))
        clip=next(c for c in manifest['clips'] if c['id']=='overspeed_warning')
        assert clip['text']=="Easy there, warrior! You're above the verified speed limit. Slow down safely!"
        assert new.getinfo('assets/voicepacks/panda_warrior_camera/'+clip['file']).file_size>5000
    unit=counts(ROOT/'app/build/test-results/testDebugUnitTest');assert unit['tests']==693,unit
    native=counts(ROOT/'app/build/outputs/androidTest-results/connected');assert native['tests']==6,native
    folder=OUT/'native/SGDelivery877ErpOverspeed'
    erp=json.loads((folder/'erp-layer-results.json').read_text())
    speed=json.loads((folder/'overspeed-session-results.json').read_text())
    assert erp['result']==speed['result']=='PASS'
    assert erp['network']['internetBlocked'] and speed['network']['internetBlocked']
    assert erp['records']==106 and erp['locations']==101 and len(erp['cases'])==6
    assert all(c['erpAboveRouteAndCasing'] and c['vehicleAboveErp'] for c in erp['cases'])
    assert speed['overspeedPlaybackStarted'] and speed['overspeedPlaybackCompleted'] and speed['continuousVoiceStartCount']==1
    assert speed['cameraScanDoesNotCancelIndependentOverspeed']
    old=json.loads((OUT/'native/SGDeliveryB877Native/delivery-flows.json').read_text());assert old['result']=='PASS'
    for clip in ('turn_right','speed_camera_ahead','red_light_camera_ahead'):
        played=json.loads((OUT/'native/SGDeliveryB877Native'/('audio-'+clip+'.json')).read_text())
        assert played['started'] and played['completed']
    accepted={'result':'PASS','build':'877 TESTING – ERP & Overspeed','baseline':'877 Official',
      'baselineCommit':BASE,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD']).decode().strip(),
      'apkSha256':sha(pathlib.Path(apk).read_bytes()),'applicationId':'com.izzyan.sgdeliveryplanner.b877erptest',
      'unitTests':unit,'nativeTests':native,'unchangedApplicationAssets':436,'mp3':209,'pcm':209,
      'erp':erp,'overspeed':speed,'preservedSilentDeliveryFlows':old,'fullyOfflineAppNetworkBlocked':True,
      'physicalDriveSpeakerBluetoothVerified':False,'officialMerge':False}
    (OUT/'acceptance.json').write_text(json.dumps(accepted,indent=2))
    report=f'''# SG Delivery #877 TESTING – ERP Layer & Overspeed
Date: 21 November 2026 (Singapore)
Baseline: #877 Official, {BASE}. Official is not modified by this testing release.
Separate package: com.izzyan.sgdeliveryplanner.b877erptest
Version: 86 / 1.4.65-b877-erp-overspeed-testing

ERP is inserted above MapLibre's annotation points layer. The native SDK inserts
route lines/casing below that layer, while the existing location stack stays above
ERP. No route geometry, camera animation, marker artwork, dimensions, coordinates,
or 106-record / 101-location database changes. The style generation also refreshes
the overlay after fast style reloads without per-GPS icon recreation.

Overspeed uses the current directional OFFICIAL road match and reliable current
GPS speed: default 3 km/h tolerance, two-second entry, one-second legal-speed exit,
five-second stale expiry. Both speed value and km/h label turn red; the separate
speed-limit circle is unchanged. Unknown, MAP DATA and GENERAL RULE limits never
produce a verified overspeed claim. The legal limit itself is never adjusted.

The original authentic overspeed_warning.mp3 is played by the existing controller.
Critical/advance guidance wins, due camera alerts can preempt overspeed, and recent
camera speech spaces safety messages. Minimum voice cooldown is 90 seconds from
actual playback STARTED, including genuine new events. Pending/rejected requests
are not marked played. Normal speed, stale GPS, reroutes, settings and route changes
cancel obsolete overspeed playback. Fresh settings default ON; stored legacy OFF
preferences are preserved. Tolerance/cooldown and independent text/voice switches
persist. Delivery completion remains silent.

Verified: {unit['tests']} JVM/Robolectric tests and {native['tests']} Android API 35
instrumentation tests passed. Native screenshots/pixel comparisons show ERP above
blue route/casing in 2D/3D, rotated/day/night views and after style reload. Original
native vehicle stack stays above ERP. 100 repeated overlay syncs create no layers.

Controlled Android Location replay enters the actual NavigationSession asynchronous
road-speed lookup using the unchanged offline catalog's verified SLE 80 km/h and
BKE 90 km/h records. Each replay also executes the normal camera scan, including
camera-master OFF without cancelling independent overspeed audio. The speed text changed red/normal, critical guidance
deferred overspeed, and overspeed MP3 playback STARTED/COMPLETED exactly once during
continuous overspeed. Unknown limits clear warnings. Existing turn/speed-camera/
red-light playback and nine silent-delivery/report phases pass as well.

Internet was blocked for the app UID using IPv4/IPv6 firewall rules, verified by
an unsuccessful connection from the same app process. Native map styles have no
network sources. These are emulator replay tests, not a physical GPS-provider
delivery test, road drive, phone-speaker listening or Bluetooth audibility check.

All 436 application assets, including 209 MP3/PCM recordings, camera/speed/postal
data and ERP attachments, are byte-identical to #877 Official. All original tests,
delivery/report/persistence code, routing/zoom logic and audio engine are unchanged.
No guessed speed limits, camera data updates, ERP voice/charges, or Android TTS.

APK SHA-256: {accepted['apkSha256']}
NO OFFICIAL MERGE. The earlier #877 delivery-silence TESTING release is retained.
'''
    (OUT/'SG-Delivery-B877-ERP-Overspeed-Testing-Report.md').write_text(report)
    print(json.dumps(accepted,indent=2))
if __name__=='__main__':
    if sys.argv[1]=='source':source()
    elif sys.argv[1]=='restore':restore(sys.argv[2])
    elif sys.argv[1]=='verify':verify(sys.argv[2],sys.argv[3])
    else:raise SystemExit('source | restore BASE | verify BASE APK')
