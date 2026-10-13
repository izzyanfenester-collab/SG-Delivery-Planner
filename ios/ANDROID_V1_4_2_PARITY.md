# SG Delivery Android V1.4.2 → Native iOS parity tracker

Reference: Android V1.4.2 merged PR #26 (main), building on Android V1.4.1 PR #25. Target is a native SwiftUI/MapKit iPhone app, not a PWA. Work is in draft PR #21 on branch ios/v3-native-swiftui. Android is not modified by this branch.

## Implemented in iOS source (still requires compilation and real-device validation)

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

## Important difference from Android V1.4.2

The existing iOS planner only produces **planned ETA**. A per-order updatedArrival date can be stored and used by the draft, but there is **not yet a live GPS/dynamic Updated ETA calculator or updater**, so the fallback to the planned ETA is expected until that feature is ported. Do not label a planned ETA as a live prediction.

## Still required before 100% Android feature parity

- Verify the CI simulator build at the latest head, plus actual iPhone installation and screenshots.
- Build native turn-by-turn GPS/voice navigation, live ETA update/rerouting, traffic cameras equivalent to Android.
- Finish cash/tax and summary reporting, XLSX, single-customer image exports, Android-equivalent reporting and history.
- Android ZIP backup/restore compatibility including proof photos (iOS JSON currently separate).
- Full configurable map-picked start/end, native camera capture, light theme, preferences parity.
- Compare UI element placement and fonts with Android screens, and test full 50-order import, route, WhatsApp, delivery, status and proof end-to-end on Singapore network/device.
- Address remote API quotas and accessibility/privacy review.

## Release gate

1. Mac CI native Simulator compilation and core smoke tests pass for the new head.
2. Actual iPhone test with imported COD orders, including two customers at one postal.
3. Complete critical parity/regression checklist above, preserving private delivery records.
4. Keep PR #21 **draft** until release criteria are met; do not silently merge into Android main.
5. Unsigned IPA from GitHub Actions is not directly installable. Standard Apple free-ID signing requires Mac/Xcode and device testing.

Historical V1.4.1 checklist: ANDROID_V1_4_1_PARITY.md.
