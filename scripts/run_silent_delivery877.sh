#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/native
# Google APIs userdebug emulator only. ADB transport is independent of its internet NICs.
adb root
adb wait-for-device
adb shell svc wifi disable
adb shell svc data disable
adb shell 'for iface in eth0 eth1 wlan0 wlan1; do if [ -d "/sys/class/net/$iface" ]; then ip link set dev "$iface" down; fi; done'
adb shell ip addr > dist/native/network-interfaces-before-tests.txt
adb shell dumpsys connectivity > dist/native/connectivity-before-tests.txt
adb logcat -c
set +e
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.SilentDelivery877Test
result=$?
set -e
# Instrumentation APK cleanup can delete internal app files; use scoped Downloads evidence.
source_dir=/sdcard/Download/SGDeliveryB877Native
if adb pull "$source_dir" dist/native/; then
  mv dist/native/SGDeliveryB877Native dist/native/b877-evidence
else
  result=1
fi
adb logcat -d -v threadtime > dist/native/android-logcat.txt
exit "$result"
