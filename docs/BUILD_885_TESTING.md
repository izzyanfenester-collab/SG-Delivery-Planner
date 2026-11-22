# Build 885 TESTING — route lanes and following direction

Exact baseline: Build 884 Official, db4686692aa41b6e9b083db65e9cb9bdf96a16c2.
The new package is com.izzyan.sgdeliveryplanner.b885test (93 / 1.4.73-b885-testing).
This branch must not update main, stable releases, update manifests or user data.

## Data and selection

The existing two-location Valhalla auto request also requests turn_lanes. The raw
OSRM-compatible response is parsed once beside the existing route/sign metadata.
Only the upcoming step's first intersection, matching that maneuver within 3m,
can supply its lane row. Intermediate intersections cannot be borrowed. The
provider's left-to-right order, indications, boolean valid and valid_indication
are retained. All valid lanes are highlighted, including active=false lanes.
Unsupported/incomplete lane data hides the entire strip instead of dropping
individual physical lanes. A missing number of lanes is never guessed. This is
upcoming-junction guidance, not lane-level vehicle positioning or a wrong-lane warning.

The existing SDK progress identifies the primary step. Then searches *after* it,
skips non-actionable road-name/status steps, accumulates their lengths, and stops
at arrival or at a 450m gap. It appears within 750m of the primary instruction;
lanes appear within 1000m. Both invalidate on reroute request, stale GPS, arrival,
destination/session change and stop. No added audio or changes to route costs,
location tracking, maneuvers, camera animation, ERP, speed or camera safety.

## Coverage — sampled, not nationwide

Two complete recorded provider responses are instrumentation/unit test inputs,
not production lane databases or bundled demonstration guidance. The baseline
response contains PIE exit 20B, Lornie Highway/Road, Upper Thomson Road and Island
Club Road. The second response was retrieved from the app's existing provider on
22 November 2026, from (1.3003,103.845) to (1.3465,103.9386), with the same auto,
OSRM, polyline6 request plus turn_lanes. It traverses Handy Road, Oldham Lane,
Orchard Road, Prinsep Street, Bukit Timah Road, CTE, PIE, Bedok and Tampines.

| Recorded route | Actionable steps | With first-intersection lane metadata | Missing |
| --- | ---: | ---: | --- |
| PIE / Lornie / Island Club | 6 | 4 | Upper Thomson exit; Island Club continuation |
| Handy / Oldham / Orchard / CTE / PIE / Tampines | 14 | 11 | Initial Handy turn; two final unnamed turns |

These 15 of 20 locations establish only sampled provider metadata availability,
not independently surveyed road markings or complete Singapore coverage. Markings
can change; follow signs. The lane strip remains hidden at every unavailable or
unsupported junction while the original main instruction remains available.

## Offline behavior

The parser and selection operate entirely locally on a loaded active route and
need no subsequent API requests. The native replays use local recorded responses
and native MapLibre route geometry with no network map sources. The latest
Official baseline does not have persisted offline turn-by-turn routes or a local
lane-aware routing engine; this focused change does not introduce either. Fresh
route requests and rerouting retain baseline connectivity requirements. No
Google/Mapbox keys, paid API or proprietary scraping are introduced.

## Verification

15 new JVM tests cover real provider lanes, shared branches, all legal lanes,
strict validity, wrong junction coordinates, missing/unsupported data, actionable
Then selection, skipped-step distance, route replacement and local operation.
All 711 existing JVM/Robolectric regressions are rerun. Two new Android API 35
tests feed geographic samples through the production map matcher/progress tracker
and SDK RouteProgress, then render actual production header/lane/Then composables
over a native MapLibre local route map. They verify white versus muted pixels,
step advancement, absent data, stale clearing and six viewport/font/map variants.
The original two History native tests also rerun, including Room record actions.
Nine new screenshots are labelled recorded GPS replay, not a physical road drive.
No fresh physical-phone, Bluetooth or driving test is claimed.

The original instruction Row, exit sign and six controls are reused, not copied
as a new mockup. Normal application launch cannot open the non-exported DEBUG
replay host. No replay assets or simulated speed limits are in the application APK.
All 436 original application assets must match Official byte-for-byte, including
209 MP3/PCM pairs, ERP 106 records/101 locations and camera/road/postal databases.
All other application source, permissions, settings and storage schemas remain
unchanged. The baseline's original Navy & Gold navigation palette is retained.

## Public provider specifications

- https://project-osrm.org/docs/v26.6.1/http.html — Lane and Intersection objects;
  first intersection corresponds to the step's maneuver.
- https://valhalla.github.io/valhalla/api/route/api-reference/ — turn_lanes and OSRM
  format. Only the actual returned OSRM lane strings/booleans are used here;
  Valhalla-native maneuver lane bitmasks are not mixed with OSRM data.
