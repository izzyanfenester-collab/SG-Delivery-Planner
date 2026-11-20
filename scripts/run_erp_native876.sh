#!/usr/bin/env bash
set +e
adb shell svc wifi disable
adb shell svc data disable
./gradlew connectedDebugAndroidTest
erp_native_status=$?
mkdir -p dist/native-map
adb exec-out run-as com.izzyan.sgdeliveryplanner.b876test tar -C files -cf - erp-876-native-evidence | tar -xf - -C dist/native-map
adb logcat -d > dist/native-map/logcat.txt
exit "$erp_native_status"
