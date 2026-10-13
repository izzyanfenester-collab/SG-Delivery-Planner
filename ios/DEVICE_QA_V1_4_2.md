# SG Delivery iOS V1.4.2 — physical iPhone QA acceptance checklist

**Status:** Not yet tested on a real device. Simulator build success and offline integration tests are not a substitute for iPhone testing. Do not mark this checklist PASSED without screenshots, actual runs, and the runner's confirmation.

## Prerequisites / install

1. iPhone running iOS 17+, Wi‑Fi/mobile data, location services enabled, WhatsApp installed, camera available.
2. Native iOS code-signing with a trusted provisioning profile. Standard free Apple ID requires a Mac/Xcode; the GitHub Actions IPA is **unsigned** and **not directly installable**. On Windows, third-party re-signing is experimental and should not receive Apple credentials through ChatGPT or GitHub.
3. Make a separate full backup of delivery records. For Android→iPhone migration, export **Backup All History ZIP** from Android History, save the ZIP to iPhone Files, then import via iPhone History → Restore Android ZIP / iOS JSON.
4. Use test orders with fictional customer names/numbers for QA. Never send test WhatsApp messages to real customers. Avoid photographing real payments in test runs.

## Test matrix — tester fills PASS / FAIL and evidence

| Area | Action on physical iPhone | Expected evidence | Result |
|---|---|---|---|
| Launch/UI | Open app, tap Home, Route, Delivery, Map, History | All five tabs and navy/gold UI respond without crash | Not run |
| Import | Paste 50 varied WhatsApp orders with COD/PayNow; include repeated postal for different customers | 50 parcels retained; original text and duplicate counts correct | Not run |
| Mapping | Optimize with live OneMap/road API | Singapore road order, valid times, delivery addresses and MapKit lines | Not run |
| GPS permission | Delivery → customer → Navigate → Built-in GPS + Voice; allow While Using | Blue user location, road polyline, voice instruction and changing ETA; no spoofed live ETA before GPS fix | Not run |
| GPS recovery | Temporarily lose signal then restore in a parked vehicle | Helpful warning, updated directions resume, no lost parcel statuses | Not run |
| Off-route | Safely make a legal alternate turn while passenger observes app | Recalculated route and ETA within routing/connectivity limits | Not run |
| WhatsApp | Click Send message on two parcels sharing a postal, **do not press Send** | Each draft uses correct original order, S$ amount and Updated ETA→+10m | Not run |
| Proof camera | Take Delivery photo and Payment photo on separate parcel IDs | Both preview, persist after app relaunch, never attach to wrong customer | Not run |
| Reports | Set Cash on Hand, Tax, SGD→MYR rate, remarks | Values persist; tax MYR rounds to two decimals and status counts match | Not run |
| Excel | History → Delivery Report → Prepare Excel; save XLSX in Files and open in Excel | Two sheets: Delivery Summary and Parcels; prices, customers, ETA and status correct | Not run |
| iPhone→Android backup | Prepare Android-compatible ZIP; restore on a **test copy** of Android database | Android recognizes ZIP, all route IDs, statuses, customers, proof images, finance retained | Not run |
| Android→iPhone backup | Export Android ZIP; import in iOS History | Previously saved routes retained, new routes added by ID, proofs/customer details visible | Not run |
| Offline + restart | Airplane mode then relaunch, review existing history | Local saved routes/photos/notes still available; live navigation/OneMap clearly needs network | Not run |
| Battery | Navigate for ~30 min with normal backgrounding/resume | No unexpected crash; measured GPS/battery drain recorded | Not run |

## Important limitations to check
- Current navigation is **foreground-only** MapKit GPS/voice guidance; it does not promise full background navigation or live traffic-camera parity with Android.
- ETA is a MapKit-derived estimate, adjusted from GPS and recalculated periodically. Actual Singapore traffic and road closures can differ.
- Proof images imported from Android ZIP may be compressed to the iOS photo-size limit; verify legibility of payment proof.
- Android-compatible ZIP contains **unencrypted private customer names, phone numbers, addresses and proof photos**. Store it securely and share only with trusted devices.
- Importing Android ZIP merges only missing route IDs. Existing iOS JSON restore is a separate operation and may replace existing iOS history. Always back up both before testing.
- Simulator camera and simulated GPS cannot prove actual on-road navigation or physical camera reliability.

## Sign-off

Device/model: __________
iOS version: __________
WhatsApp version: __________
App build/branch/commit: __________
Date/time/location of test: __________
Runner/tester: __________
Issues, redacted screenshots and failed postal examples: __________
Acceptance: **PENDING** until all critical tests pass.
