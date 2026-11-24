#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/native
# Every replay uses packaged real source geometry and route metadata; no basemap HTTP.
adb shell svc wifi disable || true
adb shell svc data disable || true
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.BuildingNative886Test,com.izzyan.sgdeliveryplanner.NavigationGuidance885Test,com.izzyan.sgdeliveryplanner.HistorySpacing884Test,com.izzyan.sgdeliveryplanner.ErpNativeMap876Test
B886_RESULT=$?
set -e
adb pull /sdcard/Download/SGDeliveryB886 dist/native/ || true
adb pull /sdcard/Download/SGDeliveryB885 dist/native/ || true
adb pull /sdcard/Download/SGDeliveryB884 dist/native/ || true
adb pull /sdcard/Download/SGDeliveryB876Native dist/native/ || true
adb logcat -d -v threadtime > dist/native/logcat.txt
exit "$B886_RESULT"
