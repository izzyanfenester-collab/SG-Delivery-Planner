# SG Delivery Android V1.4.1 → Native iOS parity tracker

Reference: Android `main` at commit `893a15640d36067a8f76ca9c1de38ff707575458`, built as SG Delivery V1.4.1. Target: Native iOS SwiftUI, NOT web/PWA. This is a working port project; **not a 100% feature-equivalent release yet**.

## Implemented in iOS source (requires simulator and device verification)
- Navy `#07172D`, royal blue `#183C78`, gold `#E4BC69`, five-tab layout and rounded cards resembling Android.
- Home: delivery date/time, eight-minute default stop service, custom start/end postal, max 50 parcels, Optimize/Clear, SG Customs link.
- WhatsApp import: English/Malay/emoji order text, original text, name/address/phone/COD/PayNow/price, selectable customers, failed-address diagnosis, edit/recheck, postal duplicates with individual customer list and DUP badge.
- OneMap postals, directed OSRM matrix + heuristic road route ordering, estimated travel buffer for Light/Normal/Heavy traffic, planned ETA and return-to-depot, route geometry when OSRM provides it.
- Route-level OneMap failure list with customer/address and edit/retry workflow; draft preserved if route is not verified.
- Route and Delivery parcel list, statuses Pending/Delivered/On Hold/Skipped, hold notes, locked Delivered status.
- Apple/Google Maps external navigation, WhatsApp message draft, delivery and payment proof photos.
- MapKit pins plus road polyline when available; route/history display; local JSON backup/restore and basic CSV export.
- Settings for traffic, routing endpoint, appearance preference and map navigation.

## Still required for 100% feature parity
- True Android UI screenshot-by-screenshot visual QA on real iPhone screens, including font/logo/icon parity and light/system theme.
- Native turn-by-turn navigation, dynamic GPS-based ETA, reroute/review and traffic camera functionality comparable to Android.
- Android-equivalent route report, customer delivery report, visual share/export formats and XLSX spreadsheets (currently CSV only).
- Android ZIP history backup / restore compatibility with iOS and proof photos; native JSON is not interoperable with Android backup.
- Full cash-on-hand/tax amount reporting, historical summary, per-customer status/hold/payment reporting and exact status rules.
- Full start/end custom location picker and travel mode selections.
- Native camera capture and photo management feature parity with Android (currently Photos picker).
- Thorough parity against all Android parcel/import, routing, payment, delivery, history, report, navigation and error-handling regression tests.
- 50-order end-to-end testing on actual Singapore roads and network calls.
- Apple ID free-signing and actual on-device installation/testing. A Windows PC cannot use Apple's official Xcode signing flow alone.

## Release gate
1. All native macOS/iOS simulator compilation checks pass for HEAD.
2. Complete all missing parity items above and add automated tests.
3. Compare screens against Android V1.4.1 screenshots at matching dimensions.
4. Test a real iPhone with 50 orders, duplicate postals, postal correction, road route, live delivery, proof and restore.
5. Keep PR #21 in draft until the parity gate is met.
6. For free Apple ID signing, access to a Mac/Xcode is normally required. Do not claim an unsigned artifact is an installable IPA.

The Android `main` branch and its APK are preserved. No PWA is created.
