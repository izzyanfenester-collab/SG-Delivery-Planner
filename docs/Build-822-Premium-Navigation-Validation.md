# SG Delivery GPS Pro — Build #822 Premium Navigation (TESTING)

Baseline: official release #820 plus the proven #821 visual map-matching work.
The official main branch, installed delivery histories and all production runner devices remain untouched.

## Implemented in this testing branch
- Navigation location updates at ~1-second target intervals while foreground and high-accuracy GPS is available.
- Conservative, direction-aware presentation-only road snapping (max 24 m with accuracy thresholds), stationary drift holding and adaptive shortest-arc bearing smoothing.
- Raw GPS remains the source for reroute, route progress, arrival and ETA decisions; snapping never fabricates physical movement or hides a confirmed off-route fix.
- A shared MapLibre geographic coordinate for the vehicle location and traveled-route visual overlay (grey traveled route, cyan remaining route), updated on the map.
- Automatic camera zoom from vehicle speed, maneuver proximity, roundabouts, high-speed roads and approach to destination; 0.32 zoom-level hysteresis and 2.5/4.5-second minimum change intervals.
- MapLibre native heading-up GPS tracking, follow padding below screen center, configurable 2D / 3D pitch and gestures that release auto-follow, with an explicit Follow button to restore it.
- Rerouting after repeated accurate, sustained large displacement from route or verified opposite-way travel, with request cooldown and failure backoff.
- Spoken turn cues using existing MapLibre progress, TTS ON/OFF and per-utterance audio volume, optional installed Malay voice (wording remains supplied by the routing engine in English), plus controls for route overview and zoom.
- Current delivery stop number/postal code in navigation display; all delivery-state writes still happen only in the existing Delivery screen.

## Not yet production-validated / not implemented
- Field driving tests over Singapore roads, Android device battery/performance measurements, intersection/parallel-road edge cases and long-session GPS loss/recovery are **not** validated by JVM tests or CI.
- Background location navigation and a legal foreground location service are **not implemented**; the activity's GPS session currently runs only while resumed, per Android lifecycle.
- An automatic transition to the next stop after status updates has not been implemented; drivers still return to Delivery and mark completion there before opening navigation for the next stop. This avoids corrupting status, COD, proof or optimized stop sequence.
- Routing currently uses the public Valhalla demo server, which is not a guaranteed production service. For a real rollout deploy a supported Valhalla instance (self-hosted or operated by a reliable provider) and point the navigation client to it. No paid TomTom/Google/Mapbox API key is required.
- Without a legitimate live-traffic provider, no synthetic red/yellow/green traffic overlay is shown.

## On-road validation before merging
- Compare arrow position against the actual route on straight roads, adjacent roads, traffic signals, U-turns, junctions and roundabouts.
- Trigger route deviation and confirm reroute, heading update, guidance and correct ETA without rearranging delivery stops.
- Change speed from 0 to 20/50/80 km/h and confirm hysteresis, zoom and tilt.
- Pan/zoom then tap Follow; toggle 2D/3D and verify camera recentering.
- Test voice English/BM availability and Bluetooth output.
- Verify original COD, PayNow, History, backups, proof photos, postal updates, route sequence and payment remain unchanged.

**Safety**: A visually snapped arrow is an estimate, not proof of actual lane position. Driver must follow road markings and legal signs.
