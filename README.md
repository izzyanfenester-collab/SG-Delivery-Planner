# IZZ Delivery

Native Kotlin / Jetpack Compose / Material 3 application for Singapore parcel delivery, package `com.izzyan.sgdeliveryplanner`. Android 8.0 (API 26) or newer; compile and target SDK 36. MVVM, coroutines, Retrofit/OkHttp, and Room provide network access, route history, location/road caches, and persistent delivery progress.

The app and launcher label are **IZZ Delivery**, with the existing premium **IZZYAN** artwork retained for the launcher icon, including adaptive icon support. The Android package `com.izzyan.sgdeliveryplanner`, `SG-Delivery-Planner` repository name, and **SG-Delivery-Planner-APK** workflow artifact identifier are retained so existing installations and download instructions remain compatible. The app uses a consistent navy and royal-blue Material 3 theme, restrained gold accents, readable rounded cards, emerald Delivered indicators and amber Current / On Hold indicators.

## Build status

The preceding app version passed unit tests, debug APK assembly and Android lint in [GitHub Actions](https://github.com/izzyanfenester-collab/SG-Delivery-Planner/actions/runs/37118623985), which uploaded the **SG-Delivery-Planner-APK** artifact. That run predates the delivery summary and hold-status changes; check the current branch's Actions run for their verification. Driver device testing and validation against real Singapore road journeys remain required before operational use.

## Use

1. Paste six-digit postal codes on Home, separated by newlines, spaces, commas or semicolons. Leading zeroes are preserved; duplicates are removed and invalid tokens are shown.
2. Choose a departure time with the Android time picker. Settings controls service minutes (default 8), traffic estimate mode, theme and routing server.
3. Optimize. OneMap resolves exact postal matches. If any postal code cannot be resolved, planning stops and reports all failed codes without discarding the input.
4. Review distance/time totals, optimized order and the horizontally scrollable schedule. START and END refer to Woodlands Checkpoint, 21 Woodlands Crossing, Singapore 738203. END is never counted as a delivery.
5. Open Delivery Mode for large controls and a prominent stop number, postal code, building, area, planned arrival, delivery period and status. NAVIGATE opens coordinate navigation. DELIVERED saves the actual Singapore completion timestamp before advancing. ON HOLD offers an optional reason; Other accepts a short note. SKIP records a skipped stop. NEXT STOP reviews the stop and advances without changing its status; a pending stop stays pending. Every change is saved with the route.
6. After every delivery has been reviewed, the app opens **IZZ Delivery Summary** automatically. It shows parcel/status counts, one-decimal success rate, start/finish, route time, distance and the planned return to Woodlands. Cash on Hand and Tax are manual SGD amounts: edit them and tap **SAVE SUMMARY** to persist them. They default to SGD 0.00 and are never calculated from deliveries or each other.
7. History shows the route date, start/finish, parcel/status counts and distance. Opening a route shows its saved summary, including Cash on Hand and Tax; use the route and delivery controls to resume or revisit stops. App restart restores saved progress and summary amounts.
8. Map shows numbered markers and road geometry, with delivery-status colors and a current-stop highlight. Ask ChatGPT is available on Home, Route Results and Delivery Mode. Saved times are planning times, not recalculated live arrival predictions.

Routes support 1–50 unique deliveries. Larger input receives an explicit error; split it into separate routes. The limit keeps all-pairs road matrix requests and route improvement bounded. A complete route remains saved and readable offline after it has been planned; new planning and navigation require appropriate connectivity. Basemap tiles already cached by osmdroid may work offline, but full offline map coverage is not promised.

## Routing and optimization

OneMap's public HTTPS address search resolves coordinates, block, road and address. Exact postal matches inside Singapore bounds are required. Addresses are cached in Room for 180 days. The fixed depot is isolated as a `Place` domain object to support later configuration.

A configurable HTTPS OSRM server supplies a **directed road driving matrix**, including distance and duration for every origin/destination pair. Cached road pairs expire after seven days and are scoped to the routing endpoint. If the entire matrix is cached it is reused. There is no straight-line fallback: routing failures or unreachable road pairs produce an explicit error instead of presenting invented driving data.

Nearest-neighbour seeds the tour. Up to 12 improvement passes examine segment reversals and pairwise stop swaps. Each candidate is evaluated as a complete directed tour, including the return to the depot. The objective combines planned seconds with a distance penalty of 15 seconds/km. This favors practical short travel and reduces long jumps/backtracking where doing so improves cost. OSRM road restrictions affect costs; the heuristic cannot guarantee a globally optimal tour, eliminate every U-turn, or prevent all repeated roads. A routed full-tour GeoJSON line supplies map geometry. OSRM snapping may put a postal building on a nearby accessible road; drivers must check building access and parking locally.

## Time calculations

All schedule times use Asia/Singapore. Each leg independently has base driving duration, traffic/junction allowance and planned travel duration. The allowance is base duration multiplied by a road-speed band (24% below 30 km/h, 16% below 50 km/h, otherwise 10%), plus 12 seconds/km capped at 8 km. Light, Normal and Heavy modes multiply that allowance by 0.6, 1.0 and 1.8. These are planning assumptions, not calibrated live traffic predictions. Zero-duration legs have zero allowance.

Arrival equals previous leave time plus the rounded-up planned travel seconds. Delivery time is added separately to arrival to produce leave time; it is never counted as base driving. Return time adds the final driving leg without delivery service. Dates appear for routes extending beyond today. Final delivery finish and return-to-depot time are shown separately. Actual delivery completion means all deliveries are marked DELIVERED; returning to the depot is not recorded as a delivered parcel. A review summary can therefore be ready while On Hold, Skipped or Pending stops remain. Success rate is Delivered / Total Parcel × 100, rounded to one decimal place.

## Delivery progress and saved summaries

Statuses are **DELIVERED**, **ON HOLD**, **SKIPPED** and **PENDING**. Reviewing a stop and completing a delivery are separate actions: NEXT STOP leaves a Pending stop pending, and the summary becomes available after every stop has been reviewed. Revisiting a stop allows its status to be changed later.

ON HOLD reasons are Customer not home, No answer, Reschedule, Payment issue, Access issue and Other. A reason is optional. Other permits a note of up to 160 characters. Status, hold reason/note, completion timestamp and review progress persist with the route in private Room storage.

Cash on Hand and Tax accept non-negative numeric values with up to two decimal places. **SAVE SUMMARY** stores them with that route; reopening History or restarting the app restores the saved values. No exchange rates, tax rates or cash calculations are applied.

The Room database remains at schema version 1 because its `routes` table already stores the route as JSON, and no SQL table or column changed. JSON decoding upgrades existing payloads safely: legacy `COMPLETED` becomes `DELIVERED`, new summary fields receive defaults, and existing route IDs, order, geometry and progress are retained. A `deliveryStateVersion: 2` marker in new JSON payloads distinguishes intentionally cleared review timestamps when a driver revisits a stop from absent legacy fields. This change does not delete the database or wipe history. Future SQL schema changes must include explicit Room migrations.

## Ask ChatGPT

The capsule help button prepares a delivery-help prompt using available route context: current/next stop, postal code, planned ETA, reviewed progress, Delivered / On Hold / Pending counts, any hold reason/note and the current route/error message. It shares that prompt to the ChatGPT Android app through an intent where supported and otherwise opens ChatGPT in the browser. A copy-context option is available if browser prefill is not honored. The driver reviews and sends the prompt in ChatGPT; answers are not fetched inside this APK. Home can open general delivery help without a route.

No OpenAI API key, token or secret is embedded in the app, and no OpenAI API account is needed by the APK. Using ChatGPT follows the account and sign-in requirements of the ChatGPT app or browser. Route details are included only when the driver taps the help button.

## Mapping services and credentials

No API credentials are needed for OneMap public address search, public OSRM, or OpenStreetMap tiles. No keys or tokens are embedded. `local.properties.example` documents the local SDK path; copy it to ignored `local.properties` for Android Studio. Secrets, keystores and actual local properties are excluded from source.

The default `https://router.project-osrm.org` endpoint is a public evaluation service with no SLA. **For operational use, host or contract an OSRM-compatible HTTPS server with Singapore driving data**, supporting table distance/duration annotations for at least 51 locations, and route GeoJSON for 52 coordinates. Set its base URL in Settings. This client does not implement authenticated provider token configuration; do not put credentials in the URL. A provider requiring authentication needs a securely configured backend or an additional client integration. Check service terms, usage limits and data freshness before deployment. OneMap availability and OSM building/road coverage can vary.

Maps use osmdroid and OpenStreetMap tiles with the app package as user agent. Attribution is visible on the map. No device-location permission is requested. Postal coordinates are sent to routing services; OneMap receives postal searches; navigation hands destinations to the chosen maps application. Local routes/progress are stored in private Room storage and Android backup is disabled. This source is not a Play Store privacy-policy submission.

## Google Maps

Per-stop navigation uses a `google.navigation:` URI and falls back to a web directions URL. Consolidated directions URLs use a conservative limit of **three delivery waypoints**, supported by mobile browsers. Google documents up to nine waypoints in some other environments, but behavior depends on client. Beyond three, the app explains the limit and offers per-stop navigation; it never truncates or reorders the saved optimized tour. Google Maps may independently choose a different road path and live duration from the planned OSRM route.

## Build locally

Install JDK 17, Android SDK platform 36 and Build Tools 36.0.0. Open this folder in Android Studio and configure the SDK path. On Linux/macOS with curl and unzip:

```sh
chmod +x gradlew
./gradlew testDebugUnitTest
./gradlew assembleDebug lintDebug
```

The launcher uses installed Gradle when available; otherwise it downloads Gradle 8.13 from the official distribution host, checks its published SHA-256, and caches it. It is a bootstrap script rather than the usual binary Gradle wrapper JAR. For a standard wrapper after installing Gradle 8.13, run `gradle wrapper --gradle-version 8.13`. Windows users can install Gradle 8.13 and use `gradlew.bat`, or use Git Bash with curl/unzip. APK output: `app/build/outputs/apk/debug/app-debug.apk`. This is a debug-signed APK; configure protected signing for a release build before distribution.

## GitHub Actions APK

Push this project to a GitHub repository. `.github/workflows/build-apk.yml` runs on pushes, pull requests or manual dispatch:

- Checks out source and installs Java 17, SDK 36 and Gradle 8.13.
- Makes `gradlew` executable, runs JUnit tests, and builds/lints the debug APK.
- Uploads **SG-Delivery-Planner-APK** and verification reports.

Open the repository's **Actions** tab, select a successful build, and download **SG-Delivery-Planner-APK** under Artifacts. Unzip to obtain `app-debug.apk`; Android may require enabling installation from your file manager. An artifact exists only after the workflow successfully builds the project.

Tests cover input normalization/duplicates/leading zeros, directed route improvement, unreachable routing data, one-delivery round trips, variable traffic estimates, sequential service timing across midnight, delivery counts/success rate, review-summary calculations and legacy JSON upgrades. Robolectric tests use a real file-backed Room database, close and reopen it, then verify hold reasons/notes, Cash on Hand and Tax, as well as preservation of existing route rows. Robolectric's first run may download its Android test runtime from Maven Central.

Before real driving, verify API compatibility, accessibility, dark mode, Google Maps and ChatGPT intent/browser handoffs, cancellation/network failure, Room restart recovery and route quality on physical devices. Use vehicle controls only while safely stopped.
