#!/usr/bin/env bash
set -euo pipefail
adb shell svc wifi disable
adb shell svc data disable
./gradlew connectedDebugAndroidTest
mkdir -p dist/native-map
# Gradle may uninstall the debug app after instrumentation; run-as cannot read its files then.
# Tests publish native screenshots and JSON into public Downloads via scoped MediaStore.
source_dir=/sdcard/Download/SGDeliveryB876Native
adb shell ls "$source_dir/B876-native-map-results.json"
adb pull "$source_dir" dist/native-map/
mv dist/native-map/SGDeliveryB876Native dist/native-map/erp-876-native-evidence
test -s dist/native-map/erp-876-native-evidence/B876-native-map-results.json
adb logcat -d > dist/native-map/logcat.txt
