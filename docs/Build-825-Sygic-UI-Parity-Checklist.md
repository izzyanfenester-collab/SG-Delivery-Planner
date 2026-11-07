# SG Delivery Build #825 — Sygic-inspired GPS UI parity and verification

**Reference comparison date:** 7 Nov 2026. Reference is Sygic's publicly documented Android navigation information hierarchy and Google Play listing, **not** copied code, graphics, fonts, or map packs:
- https://help.sygic.com/hc/en-us/articles/39347187325842-Main-application-screens-in-Sygic-Truck-Caravan-Navigation (3 Nov 2026; related Sygic product, not identical UI)
- https://play.google.com/store/apps/details?id=com.sygic.aura (GPS Navigation & Maps, Aug 2026 Android release listing)
- https://www.manula.com/manuals/sygic/sygic-professional-navigation/28.0/en/topic/lane-guidance (illustrative navigation image only)

**Important:** the attached SG Delivery screenshot described by the user is a *before* reference, never a target design. No screenshot file was provided within the connected GitHub repo, so pixel-level original-image overlap measurements are not possible. Parity here means meaningful navigation hierarchy, not proprietary pixel-for-pixel reproduction.

## Explicit UI comparison

| Sygic reference element | Previous #823/#824 GPS | New #825 implementation | Verification |
|---|---|---|---|
| Map visually dominates screen | Map is full screen but overlaid by very large UI cards | Full-bleed MapLibre under compact floating controls and translucent panels | Code implemented; physical-device screenshots pending |
| Turn guidance | Large header / redundant title | One compact (~82dp) card, arrow/distance/road/voice; no permanent GPS Pro heading | Code implemented; small phones need validation |
| Vehicle camera | GPS-follow pitched map | Retains map-matched follow and zoom with tilt; lower-central model anchor | Android road test pending |
| Real 3D car | Pre-rendered 3D-looking vector/bitmap | New OpenGL ES 2.0 depth-tested generated 3D body, glass, wheels, lights, GPU-rendered only in 3D follow; native 2D puck retained | Geometry unit tests; GPU/road validation pending |
| Map 3D buildings | Style dependent | Adds actual vector height extrusion on styles with `openmaptiles/building` where not already provided | Actual device/map source validation pending |
| Right-side control density | 5 oversized named rectangles | 48dp icon-only controls with zoom kept in expandable quick menu; short landscape hides extra controls | Code implemented; screenshots pending |
| Petrol placement | Always-visible huge Petrol card | Moved to bottom-sheet quick menu, retaining same Petrol dialog | UI wiring; device validation pending |
| Speedometer | Permanent large speed card | Small current-GPS-speed badge, only with valid speed; unknown limit is omitted | UI wiring; confirmed legal speed data unavailable |
| Bottom ETA | Large dashboard card | Compact ~84dp panel, ETA/remaining/postal 26sp, same Delivery callback | UI wiring; device validation pending |
| Traffic overlay | Local estimator / unmerged runner pilot | Preserves Build #824 actual HTTP runner-source pilot; no fabricated colors for unknown shared observations | Backend synthetic CI only; actual multi-phone service pending |
| Rerouting | Valhalla online, off-route policy | Preserved; no new offline/traffic route optimization claims | Not road verified |
| Lane/junction view | Not reliably available | Not implemented; must not invent lanes/exits | Open |
| Voice guidance | English TTS route cues | Original working TTS callbacks preserved, compact mute toggle | Road/Bluetooth tests pending |
| Offline map and route | Not available | Not implemented; quick menu explicitly disabled | Open |
| Landscape | Old large cards overcrowd | Adaptive top/bottom widths, scrollable menu, reduced controls on short landscape | Code implemented; device validation pending |
| Data preservation | Delivery records separate from navigation Activity | UI does not write delivery data; existing Delivery callback retained unchanged | Full regression/manual backup verification pending |

## Test matrix and release gates

1. **Android CI**: `app:testDebugUnitTest`, `assembleDebug`, `lintDebug` must be green for the exact #825 head SHA (check PR actions).
2. **OpenGL GPU validation**: phone running Android 10–16; check transparent overlay, actual 3D roof/body/wheels, map-match alignment, no obscured controls or unexpected touch interception.
3. **Portrait & landscape**: screenshots from actual Android devices at normal and enlarged font scales; check 48dp targets, 26sp postal, top/bottom readability, no clipping, menu scroll.
4. **Moving navigation**: follow/recenter, route overview, 2D/3D, zoom overrides, GPS loss, reroute, blue-to-grey boundary, night map, accurate speed, TTS audio.
5. **Traffic backend**: deploy real Singapore OSM road data, authenticated HTTPS runner sessions, privacy review, test >=2 moving devices on same/different road directions; do not equate synthetic server tests with road verification.
6. **Delivery regressions**: 30 stops, multiple same postals, WhatsApp import, COD/PayNow, proof, History, Payment, Backup/Restore, start/end selection.
7. **Unfinished product requirements**: offline Singapore maps and offline route calculation, reliable production router, lane/junction guidance, road speed-limit data, 3D model realism customization, traffic-aware alternatives, foreground service for background GPS.

**Final acceptance = NOT MET.** This is Build #825 TESTING; keep Build #823 on official `main`. Include only true device captures as **actual screenshots**. Do not substitute renderings or screenshots of Sygic for actual SG Delivery APK output.
