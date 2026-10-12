# SG Delivery V3 — Native iOS (SwiftUI)

This is **not a PWA**. It is a separate native iPhone app, built with SwiftUI and MapKit.
The existing Android application and the deployed V2 PWA are unchanged.

## Current implementation

- Five native tabs: Home, Route, Delivery, Map, History.
- WhatsApp text import: name, address, Singapore phone, COD/PayNow, amount, postal; same-postal separate customers; exact message deduplication.
- OneMap address lookup and OSRM road-matrix optimization (directed nearest-neighbour, 2-opt/swap); 8 minutes per customer by default; Singapore timezone.
- Customer details, WhatsApp draft (never auto-send), Google Maps handoff.
- Delivery statuses, hold reason, delivery timestamps, proof images from Photos.
- Local JSON persistence and JSON export/restore with embedded images.
- MapKit stop pins and history.

## Build and install using a free Apple ID

**A Mac with Xcode is required to compile and sign an iPhone app.** A Windows PC alone cannot build this native Xcode project. An Apple Developer Program subscription is **not** required for personal device testing via Xcode.

1. On a Mac, install Xcode from the Mac App Store and launch it once.
2. Install XcodeGen: `brew install xcodegen`.
3. Clone this repository and check out branch `ios/v3-native-swiftui`.
4. Open Terminal: `cd SG-Delivery-Planner/ios && xcodegen generate`.
5. Open `SGDeliveryV3.xcodeproj` in Xcode.
6. In Xcode > Settings > Accounts, add your free Apple ID.
7. Select target SGDeliveryV3 > Signing & Capabilities > Team: your **Personal Team**.
8. If Xcode reports a bundle identifier collision, change Bundle Identifier to a unique value (e.g. `com.yourname.sgdelivery.v3`).
9. Connect your iPhone to the Mac, trust the computer, enable Developer Mode if prompted, select your iPhone as run destination, then press Run (▶).
10. If required, approve the developer profile under iPhone Settings > General > VPN & Device Management.

Free Personal Team provisioning is limited and generally expires after **7 days**. Rebuild/reinstall from Xcode as needed.

## Not ready for production

- The public OneMap and OSRM services may reject or throttle iPhone traffic. Route planning must be tested with actual Singapore postal codes and up to 50 orders.
- MapKit currently displays **pins**, not a verified driving polyline; no built-in turn-by-turn GPS navigation.
- Proof photos are embedded in local JSON and can exceed iOS app storage/backup limits; no Android-compatible ZIP import yet.
- Traffic is an estimate, not live.
- No iPhone hardware test has been performed yet. CI simulator compilation is necessary but not sufficient.
- The iOS app requires iOS 17 or later.

Never rely on unverified ETA values for real delivery deadlines.
