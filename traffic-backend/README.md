# SG Delivery runner-powered traffic — pilot backend

This is a **real observation-processing service**, not a third-party traffic feed or a UI simulation. GPS speed measurements from consenting SG Delivery navigation sessions are validated, conservatively matched to road segments, grouped by **road and direction**, and aggregated. A segment needs at least **5 fresh observations from 2 distinct navigation sessions over 15 seconds** to be colored. Unknown or expired data is **UNKNOWN**, never invented. Samples live in RAM and expire after 15 minutes; the service does not retain a historic location trail.

**Current maturity: PILOT / NOT PRODUCTION READY.** Code and synthetic HTTP/unit tests do not prove true island-wide coverage, trusted distinct drivers, or real-road correctness. Do not expose a permanent shared pilot token to arbitrary public clients.

## Run locally

1. Obtain lawfully usable Singapore OSM road geometry, respecting ODbL and attribution. Supply GeoJSON `FeatureCollection` containing road `LineString` features with coordinates in **[longitude, latitude]** order. Include stable `id` or `osm_id`, `highway`, `oneway` and optionally calibrated `freeflow_kph`. **No proprietary Sygic data.** Exporting OSM road ways to GeoJSON requires appropriate external OSM tooling and QA; this repository does **not** ship an offline OSM extract or imply coverage.
2. `cd traffic-backend && python -m pip install -r requirements.txt`
3. `export SG_ROADS_GEOJSON=/absolute/path/to/sg-roads.geojson`
4. `export SG_TRAFFIC_PILOT_TOKEN=<random-long-pilot-secret>`
5. `uvicorn server:app --host 127.0.0.1 --port 8000 --no-access-log --workers 1`
6. `python -m unittest test_server -v` for isolated synthetic tests.

For a container: `docker build -t sg-runner-traffic traffic-backend` then `docker run -p 127.0.0.1:8000:8000 -v "$PWD/sg-roads.geojson:/data/roads.geojson:ro" -e SG_ROADS_GEOJSON=/data/roads.geojson -e SG_TRAFFIC_PILOT_TOKEN=... sg-runner-traffic`.

Deploy behind a reverse proxy with valid TLS. `GET /health` shows `ready:false` when no real road dataset is loaded. The API intentionally refuses ingestion without map data and authentication.

## API

`POST /v1/traffic/observations` with header `X-Pilot-Key`:

```json
{"sessionId":"anonymousNavigationSession0123","observations":[{"latitude":1.30,"longitude":103.80,"speedKph":33.0,"accuracyM":6.0,"bearingDeg":90.0,"timestampMs":1794000000000,"driving":true,"deliveryActivity":false}]}
```

Returns `{"accepted":1}` only for a valid observation matched to the road network. Upload at 5–10-second intervals while actively driving with permission and network disclosure. Do **not** upload addresses, names, order text, postal codes, proof images, payments, or delivery history.

`GET /v1/traffic/segments?west=103.7&south=1.2&east=103.8&north=1.3` with the same pilot header returns directional road-edge geometry in **[latitude,longitude]** pairs and `level` (`SMOOTH`, `SLOW`, `JAMMED`, `UNKNOWN`), `speedKph`, `freeFlowKph`, runner/sample counts and timestamps. BBox width/height is capped. Segments with inadequate evidence always return UNKNOWN.

## Important limits / before rollout

- **Authentication**: shared `X-Pilot-Key` only restricts a **private pilot**. Shipping it in an APK makes it extractable. Production requires per-runner authenticated enrollment, revocation, quotas, anomaly protection and privacy review.
- **Scale**: one in-memory process with one worker only; no horizontal multi-node aggregation, persistence, high availability, or rate-limit hardening yet. Integrate Redis/Postgres and service-side abuse controls before production.
- **Road matching**: uses a conservative oriented nearest-road edge. Complex parallel expressways and flyovers demand direction/elevation-aware map matching and road tests.
- **Freshness**: 10-minute aggregation / 15-minute expiry; observations are rejected when inaccurate, stale, parked/delivery activity, or implausible. One runner cannot independently color a jammed road.
- **Coverage**: data from SG Delivery runners only, unknown roads never display fake congestion. Confirm OSM licensing, data-controller transparency/consent, secure hosting, and country-specific data retention requirements.
- **Offline**: this service requires network and does not deliver offline maps or offline routing.
- **Verification**: synthetic unit tests are not real multi-runner road trials. Complete phone-to-server and outdoor dual-device validation before declaring the master specification finished.
