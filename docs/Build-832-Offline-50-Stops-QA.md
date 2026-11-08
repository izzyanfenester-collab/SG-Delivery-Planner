# SG Delivery Build #832 – Offline Singapore Road Maps and 50-Stop QA

**Status: Build #832 approved for source merge on 9 November 2026. Physical Android Airplane Mode acceptance tests NOT completed; release remains unverified on real devices.**

## Source & scope
- Base: SG Delivery Official Build #831, app version 1.4.19-b831.
- Release version 1.4.20-b832, versionCode 31, same production applicationId. Debug variants use suffix .testing.
- Added locally stored road graph, road-only MBTiles Map page display, 50-stop pre-delivery optimization and automatic offline optimization fallback.
- Existing GPS navigation, voice, camera, online rerouting, saved delivery history, payment schema, COD/PayNow, proof and backup/restore implementations are not modified.
- No new automatic offline rerouting or turn-by-turn offline navigation was implemented.

## Offline source
- Road source: OpenStreetMap contributors (ODbL), via Geofabrik Singapore-region extract, not public map tiles.
- Generated directed graph: 427,622 nodes; 712,631 directed road edges; 61,079 supported via-node turn restrictions.
- Main strongly connected driving component: 393,008 vertices, used to snap delivery addresses to connected roads.
- Known limitation: 10,069 via-way restriction relations are NOT implemented. Do not claim exhaustive OSM turn restriction support.
- Road-only visual offline basemap: 4,916 MBTiles PNGs over zooms 9–16. Not complete building/POI/label cartography.
- Data package published at https://github.com/izzyanfenester-collab/SG-Delivery-Planner/releases/tag/sg-offline-data
- Versioned ZIP tested: SG-Offline-Roads-20261108.zip, around 23.6 MB; contains binary graph, road-only mbtiles and metadata.json.
- Downloaded ZIP, graph and tile database have independent SHA-256 checksums and integrity checks before activation.
- Existing locally seeded Singapore postal database supports more than 100,000 verified postal coordinates.

## Android technical implementation
- OfflineRoadGraph: memory-mapped directed road-network with edge-state Dijkstra, one-ways, via-node restrictions and a U-turn penalty.
- OfflineRoutePlanner: up to 50 deliveries, 12 multi-start greedy tours with swap, reverse / 2-opt and relocate improvements; global optimality not guaranteed.
- Repository.planOffline: resolve postal codes, graph-based distance/cost matrix, schedule route and preserve per-order unique ID and payment/proof metadata.
- PlannerViewModel.optimize: if internet is unvalidated, use local optimizer; on transient network failure with installed package, fallback to local optimizer.
- OfflineMapPackageManager: checksum-verified ZIP + graph + MBTiles, rollback-safe activation after successful verification, old package retained, weekly network-constrained checks.
- OfflineMapSettingsUi: status/version/size/date and install/update action.
- Map page: local MBTiles while disconnected; built-in GPS activity untouched.

## Verified automated tests
- Android GitHub Actions run 42500766911 completed successfully: unit tests, assembleDebug and lintDebug.
- Synthetic graph tests cover graph format, 1-way, via-node no-turn, off-road coordinate rejection, 50 stops with fixed end and default last stop.
- Separate GitHub Actions road-package run 42500762819 succeeded and published checksum-verified map package.
- Independent C++ reference benchmark (not Android APK): 50 connected postal coordinates, deliberately randomized initial order; 826.6 km input vs 209.9 km optimized, about 74.6% improvement on same directed OSM graph. Not a guaranteed global shortest route or representative delivery workload.

## Mandatory physical Android acceptance tests (NOT VERIFIED)
- [ ] Install testing APK beside Official #831 (without overwriting Official application and personal data).
- [ ] Online install via Settings > Download Singapore Offline Maps; verify version, size, count and date.
- [ ] Enable Airplane Mode with Wi-Fi/mobile off; import exactly 50 orders, optimize without internet.
- [ ] Confirm route source labelled Offline and 50 unique orders retained; measure runtime, memory, battery and UI responsiveness.
- [ ] Test fixed Start + fixed End, default Last Delivery End, repeated postals and all order/payment/proof metadata.
- [ ] Verify route distances and reduced zigzag/backtracking against original import order with real Singapore delivery distribution.
- [ ] Verify Local Map page renders road-only MBTiles and route line without internet.
- [ ] Verify corrupted/partially downloaded package and low storage leave previous valid package available.
- [ ] Verify weekly/manual map updates with network and successful installation date/status reporting.
- [ ] Verify no modifications or regressions in built-in GPS navigation and no added offline rerouting/turn-by-turn.
- [ ] Verify Home, Route, Delivery, Map, History, Payment, Settings, exports and Backup/Restore.
- [ ] Capture and inspect actual-device screenshots.

## Release gate
Owner requested merge of Build #832 on 9 November 2026 despite outstanding physical-device acceptance checks. Merging the source does **not** certify offline 50-stop routing or map display as validated on Android. Before wide runner rollout, complete the checks above; keep the previous signed Official #831 APK available for rollback, and back up delivery data before installation. Code and published road data do not by themselves prove device correctness.
