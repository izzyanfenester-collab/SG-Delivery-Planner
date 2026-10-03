# SG Delivery Planner

Native Kotlin / Jetpack Compose / Material 3 application for Singapore parcel delivery, package `com.izzyan.sgdeliveryplanner`. Android 8.0 (API 26) or newer; compile and target SDK 36. MVVM, coroutines, Retrofit/OkHttp, and Room provide network access, route history, location/road caches, and persistent delivery progress.

## Build status

**Verified in GitHub Actions:** unit tests, debug APK assembly and Android lint all passed in [build run 4](https://github.com/izzyanfenester-collab/SG-Delivery-Planner/actions/runs/37117255783), which uploaded the **SG-Delivery-Planner-APK** artifact. Local execution remains unavailable because this workspace lacks Gradle/Android SDK and its download proxy refused connections. Driver device testing and validation against real Singapore road journeys are still required before operational use.

## Use

1. Paste six-digit postal codes on Home, separated by newlines, spaces, commas or semicolons. Leading zeroes are preserved; duplicates are removed and invalid tokens are shown.
2. Choose a departure time with the Android time picker. Settings controls service minutes (default 8), traffic estimate mode, theme and routing server.
3. Optimize. OneMap resolves exact postal matches. If any postal code cannot be resolved, planning stops and reports all failed codes without discarding the input.
4. Review distance/time totals, optimized order and the horizontally scrollable schedule. START and END refer to Woodlands Checkpoint, 21 Woodlands Crossing, Singapore 738203. END is never counted as a delivery.
5. Open Delivery Mode for large controls. NAVIGATE opens coordinate navigation. DELIVERED saves the actual Singapore completion timestamp before advancing. SKIP records a skipped stop. NEXT STOP advances without completing the pending stop. Tap a route card or the end-of-route revisit buttons to revisit a skipped/pending delivery.
6. Map shows numbered markers and road geometry; green means completed and orange means current. History reopens the saved schedule and progress. App restart restores the last open route. Saved times are planning times, not recalculated live arrival predictions.

Routes support 1–50 unique deliveries. Larger input receives an explicit error; split it into separate routes. The limit keeps all-pairs road matrix requests and route improvement bounded. A complete route remains saved and readable offline after it has been planned; new planning and navigation require appropriate connectivity. Basemap tiles already cached by osmdroid may work offline, but full offline map coverage is not promised.

## Routing and optimization

OneMap's public HTTPS address search resolves coordinates, block, road and address. Exact postal matches inside Singapore bounds are required. Addresses are cached in Room for 180 days. The fixed depot is isolated as a `Place` domain object to support later configuration.

A configurable HTTPS OSRM server supplies a **directed road driving matrix**, including distance and duration for every origin/destination pair. Cached road pairs expire after seven days and are scoped to the routing endpoint. If the entire matrix is cached it is reused. There is no straight-line fallback: routing failures or unreachable road pairs produce an explicit error instead of presenting invented driving data.

Nearest-neighbour seeds the tour. Up to 12 improvement passes examine segment reversals and pairwise stop swaps. Each candidate is evaluated as a complete directed tour, including the return to the depot. The objective combines planned seconds with a distance penalty of 15 seconds/km. This favors practical short travel and reduces long jumps/backtracking where doing so improves cost. OSRM road restrictions affect costs; the heuristic cannot guarantee a globally optimal tour, eliminate every U-turn, or prevent all repeated roads. A routed full-tour GeoJSON line supplies map geometry. OSRM snapping may put a postal building on a nearby accessible road; drivers must check building access and parking locally.

## Time calculations

All schedule times use Asia/Singapore. Each leg independently has base driving duration, traffic/junction allowance and planned travel duration. The allowance is base duration multiplied by a road-speed band (24% below 30 km/h, 16% below 50 km/h, otherwise 10%), plus 12 seconds/km capped at 8 km. Light, Normal and Heavy modes multiply that allowance by 0.6, 1.0 and 1.8. These are planning assumptions, not calibrated live traffic predictions. Zero-duration legs have zero allowance.

Arrival equals previous leave time plus the rounded-up planned travel seconds. Delivery time is added separately to arrival to produce leave time; it is never counted as base driving. Return time adds the final driving leg without delivery service. Dates appear for routes extending beyond today. Final delivery finish and return-to-depot time are shown separately. Actual completion means all deliveries are marked completed; returning to the depot is not recorded as a delivered parcel.

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

Tests cover input normalization/duplicates/leading zeros, directed route improvement, unreachable routing data, one-delivery round trips, variable traffic estimates, and sequential service timing across midnight. Before real driving, verify API compatibility, accessibility, dark mode, Google Maps intents, cancellation/network failure, Room restart recovery and route quality on physical devices. Use vehicle controls only while safely stopped.
