#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/native
adb root
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
B877_ERP_PACKAGE=com.izzyan.sgdeliveryplanner.b877erptest
B877_ERP_UID=$(adb shell cmd package list packages -U "$B877_ERP_PACKAGE" | sed -n 's/.* uid:\([0-9]*\).*/\1/p' | head -1 | tr -d '\r')
[[ "$B877_ERP_UID" =~ ^[0-9]+$ ]]
# An app-scoped internet block covers Ethernet too, without disrupting ADB/system services.
adb shell iptables -I OUTPUT 1 -m owner --uid-owner "$B877_ERP_UID" -j REJECT
adb shell ip6tables -I OUTPUT 1 -m owner --uid-owner "$B877_ERP_UID" -j REJECT
adb shell iptables -S OUTPUT > dist/native/offline-ipv4-rules.txt
adb shell ip6tables -S OUTPUT > dist/native/offline-ipv6-rules.txt
adb logcat -c
set +e
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.izzyan.sgdeliveryplanner.ErpLayerOrder877Test,com.izzyan.sgdeliveryplanner.OverspeedSession877Test,com.izzyan.sgdeliveryplanner.SilentDelivery877Test \
  -Pandroid.testInstrumentationRunnerArguments.blockedUid="$B877_ERP_UID"
B877_ERP_RESULT=$?
set -e
adb pull /sdcard/Download/SGDelivery877ErpOverspeed dist/native/ || true
adb pull /sdcard/Download/SGDeliveryB877Native dist/native/ || true
adb logcat -d -v threadtime > dist/native/logcat.txt
exit "$B877_ERP_RESULT"
