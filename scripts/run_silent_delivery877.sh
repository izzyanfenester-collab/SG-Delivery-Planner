#!/usr/bin/env bash
set -uo pipefail
mkdir -p dist/native
adb shell svc wifi disable
adb shell svc data disable
adb logcat -c
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.SilentDelivery877Test
result=$?
# Instrumentation APK cleanup can delete internal app files; use scoped Downloads evidence.
source_dir=/sdcard/Download/SGDeliveryB877Native
if adb pull "$source_dir" dist/native/; then
  mv dist/native/SGDeliveryB877Native dist/native/b877-evidence
else
  result=1
fi
adb logcat -d -v threadtime > dist/native/android-logcat.txt
exit "$result"
