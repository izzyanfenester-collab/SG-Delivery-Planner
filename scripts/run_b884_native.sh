#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/native
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.HistorySpacing884Test
B884_TEST_RESULT=$?
set -e
adb pull /sdcard/Download/SGDeliveryB884 dist/native/ || true
adb logcat -d -v threadtime > dist/native/logcat.txt
exit "$B884_TEST_RESULT"
