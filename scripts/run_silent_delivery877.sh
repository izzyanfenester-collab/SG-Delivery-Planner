#!/usr/bin/env bash
set -uo pipefail
mkdir -p dist/native
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.SilentDelivery877Test
result=$?
adb exec-out run-as com.izzyan.sgdeliveryplanner.b877test tar -C files -cf - b877-evidence | tar -xf - -C dist/native || true
adb logcat -d -v threadtime > dist/native/android-logcat.txt
exit "$result"
