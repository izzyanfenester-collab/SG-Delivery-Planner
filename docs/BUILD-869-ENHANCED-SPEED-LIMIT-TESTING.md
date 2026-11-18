# SG Delivery Build #869 TESTING — Enhanced Speed Limit Display

## Baseline / isolation
The source of this test build is the successful SG Delivery Build #868 TESTING commit `83f06bd69e7c68f822836897e3dbca0970718bb0`, descended from Official #867. Build #869 uses a separate Android application ID suffix `.b869test` and a TESTING-only prerelease. No modification or merge to Official is authorized.

## Feature
The existing lower-left Current Speed Meter is preserved. The Speed Limit circular indicator directly below it displays a large black numeric value on white inside a thick red ring, with "km/h" in smaller type, as soon as an actual GPS / named road / direction / upcoming route camera approach can be confirmed by the existing conservative matching logic.

The label **directly underneath the circle** always identifies the evidence class:
- **VERIFIED** — Only when independently verified authoritative direction-specific road-segment speed limit data is present AND a verified segment geometry match succeeds. Overspeed can highlight vehicle speed and ring after speed-accuracy filtering and hysteresis.
- **REFERENCE** — Original spreadsheet status contains "Rujukan", with numeric candidate. Show the number in the circle and small "CAMERA-AREA REF"; never a legal over-speed signal.
- **UNVERIFIED** — Original spreadsheet status describes a provisional road/segment matching ("Padanan jalan/segmen"), or other non-reference numeric status. Show the number in the circle, with "CAMERA-AREA REF"; never claim it is the posted legal maximum or trigger a legal overspeed warning.
- **UNKNOWN** — Missing numeric value or no confident match. Display a dash (—) in a neutral white/grey circular indicator. Never infer 50 km/h or 0 km/h.

The stored 100-entry original user Excel-derived database has **32 "Rujukan"** records (20 individual fixed speed + 12 average-zone), **59 "Padanan"** provisional records (13 KPE/MCE, 46 mobile/laser), and **9 "Perlu semakan"** blanks, including 12 average zone points with low-precision longitude. The original Malay status, coordinates, ID, source, notes and date stay unchanged in local SQLite.

**Critical qualification:** Matching a camera does not certify the legal speed limit for a road segment. Every one of the 91 workbook numeric limits is still provisional; this build labels reference speeds as advisory. The 12 low-precision average-zone points are retained but not converted into precise, on-road camera triggers. The source data cannot establish live electronic variable speed-limit signs or vehicle-specific restrictions.

## GPS and display behavior
The GPS session already calls `updateSpeedLimit868(fix)` on each validated Fused Location Provider fix. Build #869 reuses its same conservative `CameraApproachLogic.find` route geometry projection, actual matched road name, camera travel direction, GPS heading, accuracy, and two consecutive valid matching samples (route-ahead 0–350m). There is no new GPS polling, geofence, synthetic navigation timer or server access. A stale speed clears immediately after loss of a confident route/direction match. The next speed appears only after two consistent candidate fixes to reduce flicker. Candidate values are *camera-area references*, not assumed to apply to the entire road.

The audio system, all 209 actual Panda Warrior MP3s, 2KM primary/500M optional camera voice alerts, red-light warnings, camera database, voice priority, original GPS animations/rerouting, offline maps, themes, delivery/payment/history/tax, user preferences and backups are unchanged.

## Automated QA
Added `SpeedLimitDisplay869Test` with explicit cases covering numeric REFERENCE, numeric UNVERIFIED (including "Perlu semakan" with a numeric value), UNKNOWN blank, independently verified scenario, no visual overspeed for provisional data, opposite-lane suppression, low-accuracy and reduced-coordinate-precision suppression, stale-road value clearing and original workbook status counts. The existing 542+ Android regression tests remain active. The TESTING workflow compiles debug APK, runs all unit tests + lint, validates exact 100 row source IDs/coordinate matches, 209 unmodified ElevenLabs Panda MP3s with PCM offline and publishes only an isolated GitHub prerelease if everything passes.

Not covered by CI: actual on-road posted sign correctness, Android speaker/Bluetooth audible navigation, small-screen visual QA, changing electronic variable limits. These require road-safe hands-on validation before an Official merge.

## Version
- `versionCode = 74`
- `versionName = 1.4.57-b869-enhanced-speed-limit-testing`
- Package for debug: `com.izzyan.sgdeliveryplanner.b869test`
- Prerelease tag: `v1.4.57-b869-enhanced-speed-limit-testing`
- APK name: `SG-Delivery-B869-EnhancedSpeedLimit-TESTING.apk`

**Official #867 remains untouched. Merge prohibited without the user's explicit approval.**
