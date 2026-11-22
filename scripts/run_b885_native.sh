#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/native
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.NavigationGuidance885Test,com.izzyan.sgdeliveryplanner.HistorySpacing884Test
B885_RESULT=$?
set -e
adb pull /sdcard/Download/SGDeliveryB885 dist/native/ || true
adb pull /sdcard/Download/SGDeliveryB884 dist/native/ || true
adb logcat -d -v threadtime > dist/native/logcat.txt
exit "$B885_RESULT"
