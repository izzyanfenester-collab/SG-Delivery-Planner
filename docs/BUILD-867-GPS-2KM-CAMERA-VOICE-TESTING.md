# SG Delivery Build #867 TESTING — Screenshot GPS Camera Coordinates + 2KM Panda Voice

**Baseline:** verified `main` Build #866 Official commit `c8524f28cd5bc1b74801a0429ca119685c2f8f67`.

## Verified source / imported data
User's attached reference image lists exactly 100 published speed-enforcement coordinate records. These match camera records already bundled in Official #866, so B867 does **not** duplicate them or change GPS coordinates. `sg_speed_camera_reference_867.json` stores all 100 original IDs, exact numeric latitude/longitude, official names, road, published travel direction, and original SPF category. An independent Android startup verifier requires every record to match the verified snapshot:
- 20 fixed speed cameras;
- 13 LTA KPE/MCE physical coordinate entries including Camera A/B;
- 5 mobile speed enforcement published locations;
- 12 average-speed zone published points;
- 50 police speed laser published locations.
241 red-light records remain unchanged. Categories are not conflated and temporary enforcement deployment is never claimed.

## Trigger behavior
- During **active Built-in GPS navigation**, a validated camera must be located on the actual upcoming route, correct named road, relevant travel direction and ahead of the vehicle. The full navigation polyline is used to calculate ahead *route distance* (not straight-line range).
- For fixed/LTA speed cameras and published mobile/laser locations, main warning begins upon entering a `2,000m` upcoming-route zone (including GPS jumps from 2,200m to 1,800m and navigation starting inside the zone). The correct real prerecorded ElevenLabs clip is used: `speed_camera_ahead`, `mobile_speed_camera` or `police_speed_laser`. Only confirmed direction-specific camera approaches can use a specific enforcement voice clip.
- Optional 500m reminder uses `speed_camera_near` for fixed/LTA speed cameras or the original appropriate category clip; it does not overlap urgent navigation, claim a number when unverified or repeat the 2km primary.
- If published text cannot prove a camera's particular travel direction, a general prerecorded safety reminder can be used only under stringent actual-route/heading conditions. It must **not** claim that an unverified opposite-lane camera is operating.
- Closely paired camera A/B or opposite-direction SPF records on the *same physical site, road and category* are grouped for voice warnings only; original records and map icons remain unchanged.
- Red-light cameras preserve B866 300m camera voice behavior; red-light camera lists are NOT assigned 2km speeding alerts.
- The 12 average-speed zone coordinates remain mapped and preserved. Their entry/exit sequence cannot be inferred from arbitrary point numbering, so no fabricated 2km independent fixed-camera/zone-boundary voice is emitted.

## Controlled GPS fallback
If an active route exists but its road polyline is temporarily unavailable, the detector requires the exact GPS-matched road, GPS accuracy <=8m, bearing accuracy <=12 degrees, vehicle speed >=3.5m/s, target ahead within 12 degrees of actual bearing and *two valid successive closer GPS readings*. Only a generic original Panda safety cue may play. This distance is explicitly logged as **geographic estimate, NOT road route distance**. It never treats a radius alone as proof of direction-specific camera enforcement. With unknown road or direction, voice is intentionally suppressed and diagnostics explain why.

## Original voice and app preservation
Uses untouched existing 209 original ElevenLabs Panda Warrior clips, offline audio, B866 `MediaPlayer.STARTED` callback/cancellation/retry and navigation voice priority. Cinematic navigation camera, blue/grey route, 3D arrow, rerouting, offline maps, route optimization, Home/Delivery/History/Payment/Tax/WhatsApp/backup and existing user data are not modified. Settings include coordinate alerts and optional secondary reminder switches, persisted in the existing camera safety settings preference store.

The new Android app suffix is `.b867test`, version `1.4.55-b867-2km-testing`, so it installs separately from Official.

## Verification
- 100-coordinate exact identity/lookup and existing 241 red-light records;
- Synthetic GPS route replay crossing 2km without exact equality, optional reminder at 500m, road mismatch, parallel road, opposite/bidirectional camera record handling, independent camera groups, preemption/retry and per-camera approach reset;
- Two-sample GPS fallback confidence checks;
- CI unit tests, lint, Android APK, 209 original MP3 bytes, camera PCM and snapshot record counts;
- **NOT yet proven:** live on-road speaker, Bluetooth, GPS flyovers and long-running on-device sessions. These require manual test before Official promotion.

**This is a separate TESTING build. Never merge into Official without explicit user approval.**
