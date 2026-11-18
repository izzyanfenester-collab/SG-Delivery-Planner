# SG Delivery #868 TESTING — Live GPS Speed Limit Display

## Baseline
Build #867 Official, commit f23b7144c6468ecfb5c11e7fab850513bb0a9d96. TESTING only, debug package suffix .b868test, no changes to Official main.

## Exact attached Excel and CSV input
The user's SG_Speed_Camera_Limits_2026-11-18(1).xlsx sheet SEMUA 100 GPS was compared with the attached CSV, matching IDs, coordinates, speed fields and statuses across 100 rows. The full 13-column CSV export is preserved in app/src/main/assets/sg_speed_limits_reference_868.csv, including original camera ID/category/point/location/direction, full original latitude/longitude, reference speed, Malay verification status, GPS precision, notes, source URLs and date. These 100 points are validated against the original 341 SPF published camera records (including 241 red lights) at SQLite load time.

There are 91 speed-limit reference numbers, 9 unknown values and 12 average-speed-zone records with only 3-decimal longitude. No worksheet entry provides independently verified road-segment legal posted-limit geometry. **No numeric value in this workbook can be promoted to a confirmed legal speed limit.**

## Implementation
- Converts all 100 original Excel-derived source rows into locally indexed SQLite on first use. No repeated Excel parsing or internet connection.
- Real Built-in GPS updates call speed-limit detection independently of camera voice settings.
- Reuses the current navigation route, vehicle road and directional camera matching; the old map and Panda Warrior 209 recorded MP3s are untouched.
- Within 350 metres ahead along a genuinely matched current-road/direction camera approach, displays a small REF 80 km/h • UNVERIFIED-style label when source has a provisional figure. Never treats proximity as proof that the whole road has that limit.
- The compact SPEED LIMIT circle appears directly underneath Current Speed. A neutral dash indicates that legal limit is not independently verified; a numeric red-border legal speed sign is only permitted by separately evidenced official directional road segment geometry. The current upload contains none. No overspeed alert is activated using the 91 provisional numbers.
- The lower left speed panel, existing camera icons, 2KM advance speed camera Panda Warrior voice, 500M optional reminder, Red-Light warning, offline maps, rerouting and delivery/payment functions are retained.
- Accuracy filtering, direction checking, two matching GPS fixes and hysteresis avoid flicker. The 12 reduced-precision average speed zone coordinates are never used as a precise speed trigger.
- Retains navigation voice priority, all premium themes and saved user data.

## QA and limitations
Automated: 100 row import, 91 provisional/9 missing/12 reduced precision, coordinates and IDs versus SPF, SQLite offline store, wrong-direction and poor-GPS suppression, verified-vs-provisional gate, 209 original Panda Warrior recording identity, unit tests, lint, APK integrity.

Not yet tested physically: actual car navigation on all roads, variable/electronic speed-limit signs, Bluetooth/phone speaker, screen sizes and live posted-speed authority. No high-speed or illegal driving test is required.

Test release version 1.4.56-b868-speed-limit-testing, separate package com.izzyan.sgdeliveryplanner.b868test. Do not merge into Official without explicit user approval.
