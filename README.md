# IZZ Delivery

Native Kotlin / Jetpack Compose / Material 3 application for Singapore parcel delivery, package `com.izzyan.sgdeliveryplanner`. Android 8.0 (API 26) or newer; compile and target SDK 36. MVVM, coroutines, Retrofit/OkHttp, and Room provide network access, route history, location/road caches, and persistent delivery progress.

The app and launcher label are **IZZ Delivery**, with the existing premium **IZZYAN** artwork retained for the launcher icon, including adaptive icon support. The Android package `com.izzyan.sgdeliveryplanner`, `SG-Delivery-Planner` repository name, and **SG-Delivery-Planner-APK** workflow artifact identifier are retained so existing installations and download instructions remain compatible. The app uses a consistent navy and royal-blue Material 3 theme, restrained gold accents, readable rounded cards, emerald Delivered indicators and amber current-stop and on-hold indicators.

The app's buttons, labels, dialogs, validation messages, errors, settings, route summaries, delivery statuses, history and help text use clear English. The app keeps its own interface and native time picker in English even when the device language is Malay or another language. Addresses, proper names and notes entered by the driver are preserved as supplied. Changing the display wording does not change saved status values, settings, delivery progress, routing or history.

## Build status

The [GitHub Actions APK workflow](https://github.com/izzyanfenester-collab/SG-Delivery-Planner/actions/workflows/build-apk.yml) runs unit tests, debug APK assembly and Android lint on each push and pull request. Successful runs upload **SG-Delivery-Planner-APK** and verification reports. Tests include OneMap authentication, retries and persistent caching, encrypted token storage, real Room database close/reopen persistence and legacy payload compatibility. Driver device testing and validation against real Singapore road journeys remain required before operational use.

## Use

1. Paste six-digit postal codes on Home, separated by newlines, spaces, commas or semicolons. Leading zeroes are preserved; duplicates are removed and invalid tokens are shown.
2. Choose a departure time with the Android time picker. The **Start and end** card defaults to Woodlands Checkpoint. Tap **Change** to choose a custom map point or your saved Home; the route returns to the same location after the final delivery. Open Settings and paste your OneMap API token into the masked **OneMap API token** field, then tap **Save OneMap token**. A token is required for postal codes that are not already cached. Settings also controls service minutes (default 8), traffic estimate mode, theme and routing server.
3. Optimize. OneMap resolves exact postal matches one search at a time, reusing cached addresses first. If OneMap rejects the token, the app tells you to update it in Settings. HTTP 429 responses receive up to three retries with increasing delays; persistent throttling stops planning without discarding your input. Other postal-code failures are reported together.
4. Review distance/time totals, optimized order and the horizontally scrollable schedule. Use **Export to Excel** to save the full schedule or **Share Excel** to send it to another app. START and END refer to the Start & End location saved with that route; the default is Woodlands Checkpoint, 21 Woodlands Crossing, Singapore 738203. END is never counted as a delivery.
5. Open Delivery Mode for large controls and a prominent stop number, postal code, building, area, planned arrival, delivery period and status. **Navigate** opens coordinate navigation. **Mark as delivered** saves the actual Singapore completion timestamp before advancing. **Put on hold** offers an optional reason; Other accepts a short note. **Skip delivery** records a skipped stop. **Next stop** reviews the stop and advances without changing its status; a pending stop stays pending. Every change is saved with the route.
6. After every delivery has been reviewed, the app opens **IZZ Delivery Summary** automatically. It shows parcel/status counts, one-decimal success rate, start/finish, route time, distance and the planned return to its saved Start & End location. Cash on hand and Tax are manual SGD amounts that default to SGD 0.00 and are never calculated from deliveries or each other. Edit them and tap **Save Summary** to persist them. Once saved, a confirmation popup offers **Share Report**, **Copy Text** and **Close**.
7. History shows the route date, start/finish, parcel/status counts and distance. Opening a route shows its saved summary, including Cash on hand and Tax; use the route and delivery controls to resume or revisit stops. App restart restores saved progress and summary amounts.
8. Map shows numbered markers and road geometry, with delivery-status colors and a current-stop highlight. Ask ChatGPT is available on Home, Route Results and Delivery Mode and opens ChatGPT for a question you enter manually. Home also includes **Tax Declare**, which opens the official Singapore Customs Traveller Portal in your external browser. Saved times are planning times, not recalculated live arrival predictions.

Routes support 1–50 unique deliveries. Larger input receives an explicit error; split it into separate routes. The limit keeps all-pairs road matrix requests and route improvement bounded. A complete route remains saved and readable offline after it has been planned; new planning and navigation require appropriate connectivity. Basemap tiles already cached by osmdroid may work offline, but full offline map coverage is not promised.

## Start & End locations and Back navigation

The default Start & End remains Woodlands Checkpoint. On Home, tap **Change**, choose **Custom / Home**, then **Choose on Map**. Pan or zoom the map and tap to place or move the gold marker. **Confirm Location** saves the selected coordinates for planning; an address is shown when reverse geocoding is available. If map tiles or address lookup are unavailable, enter valid latitude/longitude and tap **Use coordinates**. Coordinates still define the route even when no address or postal code is available. No device-location permission is needed.

After choosing a custom location, use **Set Home** to store it on this device or **Update Home** to replace an existing Home after confirmation. **Use Home** selects the saved Home. Every planned route stores its own type, coordinates, label, address and postal code, and uses that location for both departure and return. Updating Home or choosing a new location changes subsequent planning; History, maps, navigation, summaries and Excel exports keep each older route's saved snapshot. Routes saved before this feature continue to use Woodlands Checkpoint.

A visible **Back** action is available on inner screens, alongside Android Back handling. It returns to the previous screen while retaining entered postal codes, selected Start & End, the saved route, progress and summary amounts. Leaving the map picker with **Cancel** or **Back** does not apply an unconfirmed point.

## Routing and optimization

OneMap's HTTPS Search API resolves coordinates, block, road and address using your Bearer token in the `Authorization` header. Exact postal matches inside Singapore bounds are required. Lookup concurrency is limited to one, including retry delays. HTTP 429 responses retry after 1, 2 and 4 seconds, with no more than three retries per postal code. A `Retry-After` value can extend a delay up to 30 seconds; longer service cooldowns stop planning so the app does not retry too early. Token errors (missing token, HTTP 401 or HTTP 403) stop immediately and direct the driver to Settings.

Successful addresses are cached in Room for 180 days and reused across routes and app restarts before reading the token or sending a search. Failed searches are not cached. Removing or replacing the token preserves saved addresses, routes and delivery progress. Each route uses its own stored Start & End coordinates. Road-pair cache keys include both endpoints' coordinates so changing the start location cannot reuse road costs from a different depot.

A configurable HTTPS OSRM server supplies a **directed road driving matrix**, including distance and duration for every origin/destination pair. Cached road pairs expire after seven days and are scoped to the routing endpoint. If the entire matrix is cached it is reused. There is no straight-line fallback: routing failures or unreachable road pairs produce an explicit error instead of presenting invented driving data.

Nearest-neighbour seeds the tour. Up to 12 improvement passes examine segment reversals and pairwise stop swaps. Each candidate is evaluated as a complete directed tour, including the return to the depot. The objective combines planned seconds with a distance penalty of 15 seconds/km. This favors practical short travel and reduces long jumps/backtracking where doing so improves cost. OSRM road restrictions affect costs; the heuristic cannot guarantee a globally optimal tour, eliminate every U-turn, or prevent all repeated roads. A routed full-tour GeoJSON line supplies map geometry. OSRM snapping may put a postal building on a nearby accessible road; drivers must check building access and parking locally.

## Time calculations

All schedule times use Asia/Singapore. Each leg independently has base driving duration, traffic/junction allowance and planned travel duration. The allowance is base duration multiplied by a road-speed band (24% below 30 km/h, 16% below 50 km/h, otherwise 10%), plus 12 seconds/km capped at 8 km. Light, Normal and Heavy modes multiply that allowance by 0.6, 1.0 and 1.8. These are planning assumptions, not calibrated live traffic predictions. Zero-duration legs have zero allowance.

Arrival equals previous leave time plus the rounded-up planned travel seconds. Delivery time is added separately to arrival to produce leave time; it is never counted as base driving. Return time adds the final driving leg without delivery service. Dates appear for routes extending beyond today. Final delivery finish and return-to-depot time are shown separately. Actual delivery completion means all deliveries are marked DELIVERED; returning to the depot is not recorded as a delivered parcel. A review summary can therefore be ready while On Hold, Skipped or Pending stops remain. Success rate is Delivered / Total parcels × 100, rounded to one decimal place.

## Delivery progress and saved summaries

Statuses are displayed as **Delivered**, **On hold**, **Skipped** and **Pending**; their existing stored values are retained. Reviewing a stop and completing a delivery are separate actions: **Next stop** leaves a pending stop pending, and the summary becomes available after every stop has been reviewed. Revisiting a stop allows its status to be changed later.

On-hold reasons are Customer not home, No answer, Reschedule, Payment issue, Access issue and Other. A reason is optional. Other permits a note of up to 160 characters. Status, hold reason/note, completion timestamp and review progress persist with the route in private Room storage.

Cash on hand and Tax accept non-negative numeric values with up to two decimal places. **Save Summary** stores them with that route before opening its confirmation popup; reopening History or restarting the app restores the saved values. The popup shows the saved route summary and offers **Share Report** through Android's share sheet for WhatsApp or other apps, **Copy Text** for the clipboard and **Close**. Copying confirms **Report copied to clipboard**. It includes route date, saved Start/End locations, start/finish times, parcel/status counts, success rate, total KM, Cash on Hand and Tax. The app does not calculate or display Net Cash, deduct Tax from Cash on Hand, or apply exchange or tax rates.

The Room database remains at schema version 1 because its `routes` table already stores the route as JSON, and no SQL table or column changed. JSON decoding upgrades existing payloads safely: legacy `COMPLETED` becomes `DELIVERED`, new summary fields receive defaults, and existing route IDs, order, geometry and progress are retained. Routes without the new stored `startLocation` safely keep Woodlands Checkpoint as both start and end; saved custom/Home routes retain their own location snapshot. A `deliveryStateVersion: 2` marker in new JSON payloads distinguishes intentionally cleared review timestamps when a driver revisits a stop from absent legacy fields. This change does not delete the database or wipe history. Future SQL schema changes must include explicit Room migrations.

## Export to Excel

The **Complete delivery schedule** section includes premium navy-and-gold **Export to Excel** and **Share Excel** actions. Export creates a genuine Microsoft Excel `.xlsx` workbook named `IZZ_Delivery_YYYY-MM-DD.xlsx`, using the route's scheduled start date. Android's document save dialog lets the driver choose a folder or document provider. A successful save shows **Excel file saved successfully**. Cancelling the save dialog keeps the route unchanged.

The `Delivery Schedule` sheet is titled **IZZ Delivery - Complete Delivery Schedule**. Its **IZZ Delivery Route Summary** includes Date, Start Location, End Location, Start Time, Finish Time, Total Parcel, Delivered, On Hold, Skipped, Pending, Success Rate, Total KM, Cash on Hand and Tax. Counts and finish time use the same summary rules as the app, and manual Cash on Hand and Tax use the values saved with that route. Save edited summary amounts before exporting to include those changes.

The schedule preserves every delivery in the app's existing order, including START at the route's saved Start & End location and END on return to that same location. Custom and Home labels, addresses and postal codes come from the saved route snapshot; changing Home later does not rewrite older exports. Its columns are Stop, Postal Code, Block, Area, Arrival, Distance From Previous, Base Drive, Traffic Buffer, Planned Travel, Delivery, Leave and Cumulative KM. Postal codes remain text to preserve leading zeroes. Excel date/time cells retain the date across midnight, distances display KM, and Cash on Hand and Tax display SGD with two decimal places. The navy-and-gold headers are bold and frozen, and columns expand to bounded readable widths with wrapping for long text.

**Share Excel** generates the same complete workbook and opens Android's share sheet, so the driver can choose WhatsApp, email, Google Drive or another compatible app. A restricted FileProvider grants temporary read access to that export only; no broad storage permission is requested. Exporting and sharing do not alter routing, delivery progress or history. The workbook contains schedule and summary data only: it excludes the OneMap token, settings credentials and map geometry. The workbook writer uses Android-compatible standard ZIP/XML APIs; Apache POI is a unit-test-only reader used to verify the resulting file independently.

## Ask ChatGPT

Every capsule **Ask ChatGPT** button opens the plain `https://chatgpt.com/` homepage in the ChatGPT Android app when supported, with a browser fallback. No question is prefilled: enter a fresh question yourself after ChatGPT opens. The existing button design and placements are retained. No postal code, ETA, current/next stop, delivery progress, route data or error message is passed, shared or copied by this action.

No OpenAI API key, token or secret is embedded in the app, and no OpenAI API account is needed by the APK. Using ChatGPT follows the account and sign-in requirements of the ChatGPT app or browser. If neither the app nor a browser can open ChatGPT, IZZ Delivery shows a clear English error and leaves the clipboard unchanged.

## Tax Declare

Home includes a large premium navy-and-gold **Tax Declare** button with a document icon. It opens the official [Singapore Customs Traveller Portal](https://m.customs.gov.sg/CustomsTravellerPortal/) using an external Android browser intent and the user's default browser. The portal is not embedded in a WebView. If a browser is unavailable or cannot open the portal, the app shows a clear English error. This action does not change the route, delivery progress, or manually entered Cash on Hand and Tax values.

## Mapping services and credentials

OneMap Search requires a current OneMap API Bearer token. Obtain or renew it through OneMap's developer account/API authentication service, then enter it in Settings. Paste the token alone or with its `Bearer` prefix. The field masks new input and never reveals the previously saved token; use **Update OneMap token** to replace it or **Remove saved token** to delete it. The app cannot renew an expired OneMap token automatically. If it is missing, expired, invalid or denied, the app provides English guidance to update it in Settings.

The token is encrypted locally with AES-GCM and an Android Keystore key before storage in private preferences. It is never hard-coded, added to route/history data or ChatGPT help, or sent to the configurable OSRM server. The OneMap client disables redirects to prevent forwarding the credential to another destination. Android backup is disabled. Public OSRM and OpenStreetMap tiles do not require a token. `local.properties.example` documents the local SDK path; copy it to ignored `local.properties` for Android Studio. Secrets, keystores and actual local properties are excluded from source.

The default `https://router.project-osrm.org` endpoint is a public evaluation service with no SLA. **For operational use, host or contract an OSRM-compatible HTTPS server with Singapore driving data**, supporting table distance/duration annotations for at least 51 locations, and route GeoJSON for 52 coordinates. Set its base URL in Settings. The routing client does not implement authenticated OSRM provider configuration; do not put credentials in the URL. An OSRM provider requiring authentication needs a securely configured backend or an additional client integration. Check service terms, usage limits and data freshness before deployment. OneMap availability and OSM building/road coverage can vary.

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

Excel regression tests open the generated workbook with an independent Microsoft Excel-compatible reader and verify the complete 50-stop schedule, START/END, exact columns, saved order and summary amounts, midnight dates, distance/duration/currency formats, frozen bold headers, readable widths and literal Unicode/formula-like address text. Custom/Home exports verify both boundary rows and the saved Start/End summary, leading-zero postal codes and exact delivery order; legacy routes retain Woodlands Checkpoint. Tests cover input normalization/duplicates/leading zeros, directed route improvement, unreachable routing data, one-delivery round trips, variable traffic estimates, sequential service timing across midnight, delivery counts/success rate, review-summary calculations and legacy JSON upgrades. OneMap tests verify the real Retrofit search path, Bearer header, credential isolation from redirects and routing, serialized requests, retry limits/backoff, cancellation, token errors and cache reuse/expiry. Token-storage tests cover encrypted persistence and rejected malformed input. English-language regression tests verify native dialog captions on a Malay device configuration, preservation of dark mode and font scaling, and English error guidance when a service returns another language. Robolectric tests use real file-backed Room databases, close and reopen them, then verify cached postal addresses, hold reasons/notes, Cash on hand and Tax, as well as preservation of existing route rows. Robolectric's first run may download its Android test runtime from Maven Central.

Before real driving, verify API compatibility, accessibility, dark mode, Google Maps and ChatGPT intent/browser handoffs, document saving and Excel sharing with installed apps, cancellation/network failure, Room restart recovery and route quality on physical devices. Use vehicle controls only while safely stopped.
