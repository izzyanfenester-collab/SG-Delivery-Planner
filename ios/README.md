# SG Delivery V3 — Native iOS (SwiftUI)

This is **not a PWA**. It is a separate native iPhone app, built with SwiftUI and MapKit.
The existing Android application on `main` is unchanged. GitHub Pages/PWA remains discontinued.

## Current implementation

- Five native tabs: Home, Route, Delivery, Map, History.
- WhatsApp text import: name, address, Singapore phone, COD/PayNow, amount, postal; same-postal separate customers; exact message deduplication.
- OneMap address lookup and OSRM road-matrix optimization (directed nearest-neighbour, 2-opt/swap); 8 minutes per customer by default; Singapore timezone.
- Customer details; Android V1.4.2-style **Send message** launches a specific customer's editable WhatsApp draft with S$ parcel amount, genuine Updated ETA where available (otherwise planned ETA) through +10 minutes, and the exact imported order. Does not auto-send. Missing price/order/ETA/phone blocks inaccurate drafts; Apple/Google Maps handoff.
- Delivery statuses, hold reason, delivery timestamps, proof images from Photos.
- Local JSON persistence and JSON export/restore with embedded images.
- MapKit stop pins and OSRM driving polyline when available; history, CSV export and iOS JSON backup.
- Native navy / royal blue / gold UI modeled after Android V1.4.2, with WhatsApp failed-order editing and duplicate-postal review.
- For exact parity status, see [Android V1.4.2 parity tracker](ANDROID_V1_4_2_PARITY.md).

## Build and install using a free Apple ID

**macOS/Xcode is required to compile and code-sign natively through Apple's standard workflow.** GitHub Actions can produce an **unsigned IPA** even for a Windows user. An unsigned IPA **cannot be installed directly on iPhone**; it needs valid signing and provisioning. Free Apple ID installation through Xcode requires Mac access; third-party Windows sideloading tools are experimental alternatives and are not guaranteed to work.

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

## Windows-only testing path (experimental)

1. Open GitHub Actions > **Build SG Delivery Native iOS Unsigned IPA** and run the workflow on branch `ios/v3-native-swiftui`, or use the PR build artifact once successful.
2. Download artifact `SG-Delivery-V3-UNSIGNED-IPA` and unzip the GitHub artifact to obtain `SG-Delivery-V3-UNSIGNED.ipa`.
3. A Windows sideloading tool capable of signing unsigned IPAs with a free Apple ID (for example Sideloadly) may be used to sign and install the app to a connected iPhone. Follow the tool vendor's official instructions and evaluate the security implications before entering Apple credentials. **This path has not been verified for this app.**
4. Free-account app provisioning normally expires after seven days and may need periodic reinstalling/resigning.

**Do not share Apple ID credentials, app-specific passwords, or two-factor authentication codes in GitHub, ChatGPT, or project files.**

## Not ready for production

- The public OneMap and OSRM services may reject or throttle iPhone traffic. Route planning must be tested with actual Singapore postal codes and up to 50 orders.
- MapKit displays route geometry **when OSRM returns it**. Built-in turn-by-turn GPS navigation and actual-device routing tests are still missing.
- Proof photos are embedded in local JSON and can exceed iOS app storage/backup limits; no Android-compatible ZIP import yet.
- Traffic is an estimate, not live.
- No iPhone hardware test has been performed yet. CI simulator compilation is necessary but not sufficient.
- The iOS app requires iOS 17 or later.
- Updated ETA on iOS is a **stored optional** value; no live GPS ETA updater is implemented. When absent, Send message clearly uses the planned ETA.

Never rely on unverified ETA values for real delivery deadlines.
