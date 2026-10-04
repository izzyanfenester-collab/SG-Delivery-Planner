# IZZ Delivery for iOS

Native SwiftUI application for iOS 17+, branded **IZZ Delivery**, bundle ID `com.izzyan.sgdeliveryplanner.ios`. The original IZZYAN artwork, navy cards, gold controls, green Delivered and amber On Hold/current-stop indicators are retained. Android's application ID, sources, database, build files and APK workflow are unchanged.

## Open and build on a Mac

Install Xcode 16+ with an iOS simulator, JDK 17, Gradle 8.13 and XcodeGen (`brew install xcodegen`). Select Xcode with `xcode-select` and finish its first-launch setup.

```sh
./gradlew -p shared jvmTest
./ios/scripts/generate-project.sh
open ios/IZZDelivery.xcodeproj
```

Choose the **IZZDelivery** scheme and an iPhone/iPad simulator. Xcode's pre-build phase runs the independent KMP build and links the static `IZZShared` framework. No Android SDK, CocoaPods or OneMap/OpenAI key is needed for an iOS build. The generated project and 1024px opaque app icon are reproducible from the checked-in XcodeGen spec and existing branding artwork. Disable user script sandboxing (already configured in the spec) to allow Gradle to build the framework. Do not open the repository root as an iOS Gradle build; the Android and shared builds have separate settings.

The macOS GitHub Actions workflow runs KMP JVM tests, generates the Xcode project, builds/tests the unsigned simulator app and reads an actual Swift-generated XLSX fixture independently with openpyxl. It uploads the simulator `.app`, test result bundle and shared reports. A simulator artifact is not an IPA and cannot be installed on an iPhone.

## Shared business logic

`shared/src/commonMain/kotlin/com/izzyan/delivery` contains postal normalization/validation, the directed nearest-neighbor/reversal/swap optimization heuristic, traffic allowances, epoch-second schedule arithmetic, delivery/status/review transitions, one-decimal success rate, route summaries and separate manual SGD Cash on Hand/Tax models. It exposes a JSON `DeliveryEngine` facade to Swift and compiles for JVM, iOS devices, Apple Silicon simulators and Intel simulators. Swift DTOs are transport/persistence mirrors; business decisions use the framework.

The algorithms were ported from the existing Android implementation. Android continues to use its existing logic to meet the requirement that the app remain unchanged. Adopting the JVM artifact in Android is a future migration requiring its existing regression suite; this branch does not replace Android's Room, Gson or java.time models. Android and iOS local histories are separate; there is no cross-device synchronization or Android database import.

## Implemented flows

- Home with six-digit postal input, duplicates removed, 1–50 stops, Singapore departure date/time and route optimization.
- Start & End defaults to Woodlands Checkpoint. Choose on Map, coordinate fallback, cancel without applying, Set/Update Home and Use Home. Each route keeps its own location snapshot.
- Complete Delivery Schedule including START, every optimized stop and END, all 12 timing/distance columns, route totals and a numbered MapKit map using OSRM road geometry.
- Delivery Mode with Apple Maps navigation, Delivered timestamp, On Hold with optional standard reasons/160-character Other note, Skipped, Pending and Next Stop preserving status. After all stops are reviewed it shows the summary; summary links allow revisiting stops.
- Summary preview, separate Cash on Hand/Tax editing and save confirmation, native Share Report and Copy Text, genuine formatted Excel export through the iOS document exporter and Share Excel through the native share sheet.
- Persistent History and Settings for service time, traffic mode and HTTPS routing endpoint.
- Tax Declare opens the official Singapore Customs Traveller Portal externally. Ask ChatGPT opens only `https://chatgpt.com/`, with no prefilled prompt, route data or clipboard modification.

## Networking, persistence and exports

OneMap exact Singapore postal matches are resolved sequentially with no Authorization header. Successful lookups are cached for 180 days. HTTP 429 retries after 1/2/4 seconds, respecting longer Retry-After delays up to 30 seconds and stopping for longer cooldowns. Road-pair costs are cached for seven days by endpoint and both coordinates. OSRM supplies the directed distance/duration matrix and geometry; there is no straight-line routing fallback. The public default OSRM service has no operational SLA. Configure a Singapore-capable HTTPS provider before real delivery use.

State and network caches use atomic writes in Application Support, complete iOS file protection and backup exclusion. Data remains local to the app sandbox and is unavailable while the device is locked. Corrupt/unreadable state is preserved and cannot be overwritten by a new empty session. Save failures surface an error and do not publish unsaved progress. No device location, contacts or broad storage permission is requested. MapKit receives map interactions; OneMap receives postal searches; the configured routing server receives coordinates; Apple Maps receives navigation destinations. Offline planning/basemap coverage is not promised; saved route data is readable offline after unlocking.

Excel uses an actual ZIP/OOXML workbook, navy/gold frozen headers, bounded column widths, independent numeric SGD cells, full Singapore date/time values and postal codes/literal addresses as text. Saved summary amounts are used; save edits before export. Temporary share files have complete file protection and are deleted on sheet dismissal. Canceling a picker/share does not change route data.

## Apple signing and TestFlight

Simulator CI is unsigned. For a physical device or TestFlight:

1. Enroll in the Apple Developer Program, choose your Apple team and register an available bundle ID (keep the IZZ Delivery display name).
2. Set `DEVELOPMENT_TEAM` locally in Xcode or through protected CI configuration; use automatic signing or a distribution certificate and provisioning profile. Never commit certificates, profiles or private keys.
3. Create the IZZ Delivery App Store Connect record, set a unique build number, archive for generic iOS devices and distribute through Xcode Organizer or a separately configured authenticated release workflow.
4. Complete privacy disclosures/privacy policy, screenshots, export-compliance questions and TestFlight beta review as required. This branch does not upload a build or create signing credentials.
5. Verify on a physical iPhone/iPad: MapKit picker, route map, dynamic type/VoiceOver, light/locked-device conditions, delivery persistence after relaunch, real road journeys, mapping throttling/network failure, Apple Maps/browser handoffs, document cancellation and Excel sharing into installed apps.

This is a native implementation, not a claim of physical-device or TestFlight validation. Inspect CI results for actual compiler/test status.

## Unsigned iOS device IPA for personal testing

The separate **Build IZZ Delivery unsigned device IPA** workflow (`.github/workflows/build-ios-ipa.yml`) builds Release with `-sdk iphoneos` and `-destination generic/platform=iOS`. It disables signing and does not use an Apple Developer Program account, signing certificate, provisioning profile or repository signing secrets. The existing simulator build/test workflow is unchanged.

After a successful run, download the **IZZ-Delivery-iOS-IPA** artifact and unzip the artifact ZIP to obtain `IZZ_Delivery_unsigned.ipa`. This is a device app in a genuine `Payload/IZZDelivery.app` IPA archive. CI verifies the arm64 Mach-O device platform, bundle metadata, lack of a provisioning profile/bundle signature, ZIP integrity and preserved executable permissions. It is not a simulator app or a TestFlight/App Store submission.

An unsigned IPA cannot be installed directly by iOS. Import it into your personal sideloading tool (for example, a compatible AltStore or Sideloadly setup), which must sign/provision it for your device. Tools may support a free Apple ID without paid Developer Program membership; account login, provisioning restrictions and periodic re-signing are handled by that tool. This workflow does not request or store Apple account credentials. Physical-device installation/runtime testing remains separate from CI's device build verification.

For local packaging on a Mac after the same unsigned device build, run:

```sh
python3 ios/scripts/package-device-ipa.py \
  ios/DeviceDerivedData/Build/Products/Release-iphoneos/IZZDelivery.app \
  ios/ipa-dist/IZZ_Delivery_unsigned.ipa
```
