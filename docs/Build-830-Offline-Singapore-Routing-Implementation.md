# SG Delivery Build #830 — Real Offline Singapore Routing: implementation and acceptance gates

**Baseline:** Build #829 Testing (`feature/build-829-payment-table-history-header`); Official main remains #823. **Do not claim Build #829 is Official until explicitly approved/merged.**

## Verified baseline audit
- `NavigationRouting.kt`: remote Valhalla endpoint (`https://valhalla1.openstreetmap.de/route`) for new driving routes; online OpenFreeMap style (`https://tiles.openfreemap.org/styles/liberty`). Cached online routes are not an offline routing engine.
- `Data.kt`: route planning relies on remote routing distance tables and route geometry, despite a limited local `OfflinePostalIndex`. Postal lookup alone does not provide road routing.
- `BuiltInNavigationActivity.kt`: MapLibre navigation and local GPS handling exist, but no verified installed offline routing graph and map style assets.
- `app/build.gradle.kts`: Build #829 Testing, Android minSdk 26; existing MapLibre navigation-core-android 5.0.0, MapLibre Android SDK 13.5.0.
- Existing application data, proof, history, COD/PayNow and payment must be preserved. No changes to data schemas or core UI for this foundation stage.

## Definition of real offline readiness
A local package must contain: (1) legally redistributable map vector assets, (2) on-device car routing graph with one-way and turn restriction information where supported, (3) validated Singapore postal coordinate index, (4) package manifest and hashes, (5) maneuver/instruction data or an on-device instruction generator. **All five must be available and verified before displaying 'Offline Maps & Routing Ready'.**

### Licensing / provenance gates
- Source OSM extract from a provider that permits redistribution and follow attribution requirements; respect ODbL and derived database obligations.
- Never bulk-download tiles from public online tile services to fabricate an offline package.
- Singapore six-digit postal locations need a legally licensed, verified dataset; OSM coverage alone is incomplete. Preserve previously verified coordinates, never invent a postal match.
- Version every dataset with source URL, timestamp, region boundary, checksums and license metadata.
- Evaluate GraphHopper/Valhalla-compatible on-device options against current Gradle/Kotlin/Android dependencies and memory/CPU constraints before selecting an engine. **A backend-only routing engine is not an offline solution.**

## Architecture (planned, not yet implemented)
- `OfflinePackageManager`: install / verify / activate / rollback package; atomic swap; previous package retained until successful activation; resumable updates.
- `OfflineRoutingEngine`: on-device road snapping, car road costs, restrictions, geometry and turn-by-turn maneuvers.
- `RoutingProvider`: Online / Offline providers with explicit capability checks, connectivity failure hysteresis and deterministic fallbacks. Do not treat 'connected to Wi-Fi' as proof the remote route API works.
- `OfflinePostalResolver`: validated six-digit postal index with provenance and unresolved/manual-pin workflow.
- `OfflineRouteMatrix`: road-network distances/times for 1–30 physical visits; cache by graph version + vehicle profile + snapped nodes.
- `DeliveryTourOptimizer`: fixed Start/End constraints; nearest-neighbor / multi-start, 2-opt / local search with road-network costs; route shape and clustering assessed using actual graph costs.
- `NavigationSession`: completed stops and order identities immutable; recompute pending unlocked stops from live GPS, preserve multiple orders at same postal, stop proof and COD state.
- `OfflineEta`: graph speeds + locally observed movement, never label offline values as live traffic.
- `TrafficSync`: only if a real shared runner backend and user consent exist; local speed estimates must not be mislabeled as crowdsourced traffic.

## Delivery stages
1. **Foundation:** inventory source, select compatible routing engine and map/postal dataset licenses; define package format, manifest and deterministic checksum/rollback tests.
2. **Map & graph:** produce verifiable Singapore graph/vector package; local installer, status, version, Wi-Fi update policy; include legally sourced postal index.
3. **Offline single route:** graph-based A-to-B car route with geometry, one-way/turn restrictions, instructions, offline ETA; MapLibre offline rendering.
4. **30-stop tour:** graph-based distance matrix; multi-start heuristic + local improvement; preserve all order IDs, duplicate postals, fixed Start/End, completed and locked stops.
5. **Automatic failover/rerouting:** off-route detection, online API timeout, hysteresis, route continuity, no silent resequencing, safe recovery on reconnection.
6. **Device QA and release:** airplane-mode cold start, 30-stop optimize, wrong turn, connectivity loss/recovery, data integrity, battery/storage performance, package update interruption, map attribution, regression of all existing features.

## Mandatory validation
- Airplane mode with GPS enabled; app restarted and no cached route available; calculate a new 30-stop route from locally installed graph.
- Confirm **no network requests** during offline route creation and maneuver generation.
- Compare travel distance and illegal turns against a documented baseline for representative Singapore deliveries; zero false promise of guaranteed shortest path.
- Test same postal multiple orders, COD/PayNow, delivery proof, completed stops, fixed End and skipped/on-hold orders.
- Force kill during package update; old package remains usable.
- Compare route geometries, maneuver instructions, ETA and rerouting on a physical Android device with airplane mode.
- Run Android unit tests, compile, lint and screenshot QA.
- Do not merge, label Official, or claim complete until real device verification passes.

## Current status
**Foundation audit and branch creation only.** No on-device routing graph, downloadable Singapore map package, airplane-mode proof, or validated installable offline APK is delivered by this document. The current Build #829 functionality must not be misrepresented as truly offline.
