# SG Delivery Build #824 — runner traffic validation ledger

**Source baseline:** Build #823 Official / `main` (merge SHA `538b0669cdd86af3e7177bbb524b0f98190b9383`).

**Status:** DRAFT DEVELOPMENT, **NOT** merged as official, **NOT** production verified.

## Implemented in the Build #824 testing branch

- GPS-only anonymous navigation-session samples, gated by precise location permission and explicit in-app traffic sharing disclosure. No addresses, names, proof, orders, payment fields or history uploaded.
- Android HTTPS client: queued GPS observations from the active navigation session, ~45-second background networking *while the activity is running*, and polling for real shared road observations. No fabricated congestion when missing.
- Backend: directional OSM GeoJSON road-edge index, orientation/accuracy/time filtering, moving/parked/delivery checks, median speed, 2 independent sessions, minimum sample count and elapsed observed duration, 10-minute aggregation and 15-minute expiry.
- Colored verified road segment overlays under the remaining blue route; no UNKNOWN color misleadingly shown as live.
- Road/direction-matched dynamic ETA corrections only on the upcoming navigation route; unobserved roads retain baseline estimate. Simple cap prevents a short observed edge affecting an entire long route geometry segment.
- Backend HTTP/unit tests and Android observation/ETA unit tests.
- Backend Dockerfile and setup instructions.

## Automated test evidence

- Runner backend synthetic tests: GitHub Actions workflow `Runner Traffic Backend Tests`. Includes multi-session aggregation, one-session UNKNOWN, parked/delivery exclusion, directional separation, expiration, authentication, malformed bbox.
- Android: the existing workflow `Build IZZ Delivery APK` runs Kotlin unit tests, APK build and lint. Check the latest run attached to the PR before claiming green.
- Synthetic tests are **not** actual runner road validation and not evidence that the mobile client can reach a deployed backend.

## Required operational prerequisites

1. Lawfully obtain and QA Singapore OSM road geometry as GeoJSON (including road IDs, highway categories and oneway information). Calibrate class-based free-flow baseline speeds against real, permitted data.
2. Deploy the service to an HTTPS endpoint with a real road dataset; pilot auth key only for a trusted private cohort. Provide trusted endpoint and pilot key as `SG_TRAFFIC_ENDPOINT` / `SG_TRAFFIC_PILOT_TOKEN` **to a controlled build environment**. Never add deployment keys to the repository.
3. Provision secure per-runner authentication, revocation, server abuse mitigation, monitoring and scalability **before public production**; build-time shared token is extractable and pilot only.
4. Verify with at least two Android phones actively driving separate sessions of the same road/direction. Confirm traffic coloration, invalid GPS filtering, time expiry, off-route/parallel road rejection, and no false congestion when a runner stops to deliver.
5. Confirm Delta ETA vs observed traffic trend over repeated drives, with controlled real-world measurements.
6. Complete COD/PayNow, proof/history/payment, backup/restore and 30-stop route regression tests on actual devices.
7. Implement the separate unfinished requirements: properly licensed offline Singapore map packages **and offline routing engine**; navigation foreground service; lane/junction visuals with reliable data; reliable production routing endpoint; traffic-aware alternative route selection and automatic rerouting; on-road camera/3D/voice performance validation.

## QA gates

| Requirement | Automated evidence | Real-world gate |
|---|---|---|
| GPS collection filter | Kotlin tests and server tests | Outdoor driving + stationary exclusions |
| Multi-runner aggregation | Synthetic two-session server test | Two separate Android devices |
| Colored traffic/unknown | Backend response and source review | Real map overlay on phone |
| Dynamic ETA | Kotlin tests | Traffic-sensitive ETA on route |
| 3D vehicle, zoom, blue/grey | Pre-existing #823 tests | On-road GPS alignment |
| Offline route/tiles | Not implemented | Not passed |
| Background navigation | Not implemented | Not passed |
| Delivery feature regression | Existing CI only | Manual regression not passed |

**Release rule:** never merge #824 as an official baseline, distribute as final production APK, or claim complete master implementation until operational and on-road gates are passed. The pilot may be used only for clearly labelled limited tests.
