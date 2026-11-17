# SG Delivery Build #865 TESTING — implementation checkpoint

Baseline: B864 Official main merge commit 52e5d6f39cc338f9b16f493e0d77637d7e34ad7f.
This is an isolated development branch. DO NOT merge into main or publish an Official APK.

Official data sources:
- https://www.police.gov.sg/Knowledge-Hub/Traffic/Traffic-Matters/Speed-Enforcement-Camera-Locations
- https://www.police.gov.sg/knowledge-hub/traffic/traffic-matters/red-light-camera-locations

Implemented at this checkpoint:
- Strict camera categories and provenance-aware nullable speed limit model.
- Conservative approach-evidence gate (road, direction, route-ahead and GPS accuracy).
- Unit tests preventing guessed speed limits, red-light speed enforcement assumptions and proximity-only warnings.

NOT YET IMPLEMENTED OR VERIFIED:
- Complete validated SPF data ingestion (including 241 red-light records).
- Local persistent database, weekly update pipeline, route geometry matching, map icons, settings UI.
- Real camera event integration and authentic new ElevenLabs Panda Warrior recordings.
- Full Android unit/lint/build checks, on-device GPS route replay and signed TESTING APK.

Do not enable alerts or represent this as a finished camera safety feature until all acceptance tests pass.
