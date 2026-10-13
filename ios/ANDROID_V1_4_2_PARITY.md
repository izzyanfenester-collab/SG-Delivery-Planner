# SG Delivery Android V1.4.2 → Native iOS parity tracker

Reference: Android V1.4.2 merged PR #26 (main), building on Android V1.4.1 PR #25. Target is a native SwiftUI/MapKit iPhone app, not a PWA. Work is in draft PR #21 on branch ios/v3-native-swiftui. Android is not modified by this branch.

## Implemented native iOS source (hardware verification pending)

- Navy/royal blue/gold five-tab shell: Home, Route, Delivery, Map, History.
- Up to 50 WhatsApp customer parcels, separate customers at a repeated postal, duplicate/postal correction details, original message retention, and manual postal import.
- OneMap postcode resolution, directed OSRM routing cost matrix with heuristic route ordering, planned Singapore ETA and a configurable minutes-per-stop allowance.
- Delivery per-customer review, statuses, proof photos imported from Photos, WhatsApp and Apple/Google Maps links, map stop pins / OSRM geometry where returned.
- JSON local records and backup/restore, history and CSV route export.
- **Android V1.4.2 Send message parity (source implementation):** a prominent button in the selected customer's Delivery card opens WhatsApp using native URL scheme first, then official wa.me fallback. The editable message includes:
  - The selected customer's **S$ amount (two decimals)**; refuses a draft when missing.
  - **Updated ETA -> 10 minutes later** in Singapore local time if updated ETA is genuinely provided; otherwise the **planned arrival -> 10 minutes later**.
  - The customer's **original imported WhatsApp order text** as stored, between separator lines. Refuses a draft when missing.
  - The COD cash reminder and thank-you text matching Android V1.4.2.
  - Specific order-ID binding prevents a customer with a shared postcode from receiving another customer's order.
  - No automatic sending.
- Core tests cover exact order isolation, amount, time format, midnight rollover, URL encoding of emoji/newlines, invalid/missing input, and backward-compatible JSON without new optional ETA.

## Android V1.4.2 follow-up implemented in this branch

- **Foreground GPS navigation** via Apple MapKit / CoreLocation: current position, road polyline, Apple MapKit turn steps, spoken voice, periodic rerouting, ETA from road distance and estimated travel time.
- **Updated ETA saved automatically** for the selected parcel and propagated to downstream uncompleted parcels while foreground navigation runs. WhatsApp uses Updated ETA + 10 min where present, otherwise planned ETA. No fake live ETA without GPS.
- **Native camera proof** for Delivery and Payment, plus Photos picker, normalized JPEG and per-order persistence; a simulator has no physical camera.
- **Cash/tax/summary** includes actual recorded vs planned finish, COD collected total, editable SGD cash/tax, SGD-to-MYR positive exchange rate, converted tax and remarks.
- **Real XLSX export** with Delivery Summary and Parcels sheets; strings stored as spreadsheet inline strings, avoiding formula injection.
- **Android HistoryBackup v1 ZIP format** import/export with manifest, SavedRoute JSON, order IDs and proof JPEGs; imported photos can be recompressed to iOS limits. Import merges only new route IDs and explicitly reports missing proof files. Android→iOS and iOS→Android device-to-device restore still need QA.
- Native simulator integration tests now exercise ZIP + photos, XLSX internal sheets, camera JPEG processing and GPS/ETA maths. Separate smoke tests cover order isolation and Android route JSON round-tripping.

## Still required before 100% Android parity and production

- **Real iPhone test**: actual GPS drive, spoken steps, camera hardware, WhatsApp, 50 orders, offline/restart, actual network errors, battery and proof persistence.
- **Actual Android↔iPhone ZIP migration test** on two devices to check full field parity and non-lossy photos; iOS recompresses proofs for app storage.
- Full **background voice navigation**, Android-equivalent live traffic cameras and rich navigation alerts are not implemented. Current GPS runs while built-in navigation is visible.
- Exact Android single-customer image reporting, advanced cash/tax summaries, status workflows and full export formats still require comparison.
- Full custom map-selected start/end picker, true light/system appearance, and screenshot-by-screenshot font/logo alignment.
- Accessibility review, OneMap/OSRM availability and rate limiting checks. Test on real Singapore roads; estimates are not guaranteed arrival times.
- See **DEVICE_QA_V1_4_2.md** for the pass/fail hardware checklist.

## Release gate

1. Mac CI native Simulator compilation and core smoke tests pass for the new head.
2. Actual iPhone test with imported COD orders, including two customers at one postal.
3. Complete critical parity/regression checklist above, preserving private delivery records.
4. Keep PR #21 **draft** until release criteria are met; do not silently merge into Android main.
5. Unsigned IPA from GitHub Actions is not directly installable. Standard Apple free-ID signing requires Mac/Xcode and device testing.

Historical V1.4.1 checklist: ANDROID_V1_4_1_PARITY.md.
