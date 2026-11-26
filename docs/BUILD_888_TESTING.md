# SG Delivery #888 TESTING — Coloured Buildings & Green Vegetation

Baseline: B885 Official, c9c824b97de50465a2a4f60b7be0522761b3d155.
Verified against the current main branch and latest stable release on 26 November 2026.
Package: com.izzyan.sgdeliveryplanner.b888test; version 96 / 1.4.76-b888-testing.

Six stable colours (Dusty Blue, Soft Teal, Sage Green, Muted Lavender, Warm Beige,
Soft Champagne) are applied to the existing 2D buildings and native 3D extrusions.
Building opacity is 60%; native roof/wall lighting, geometry and heights stay intact.
Night uses corresponding subdued colours with the same feature grouping.

Existing named tree/forest/grass/park layers receive natural greens; separate
tree-trunk layers receive brown. Circle, fill, line and extrusion paints are
supported, plus native SDF tree icons. Full-colour tree sprites retain their original
artwork. No new tree positions, geometry, tile sources, models or downloads are added.
The captured real Woodlands and Marina tiles contain woodland and grass polygons,
but no individual-tree layer. Individual-tree recolouring is therefore verified
with a separate synthetic native-renderer probe, excluded from location screenshots.

Only the basemap scenery paint entry point and new scenery policy change in
production source. Route/camera animation, voice, alerts, ERP, panels, delivery,
payments, history, settings, schemas and all 436 Official assets stay intact.
Existing style-ready and day/night callbacks reapply the scenery colours.

Acceptance requires fresh unit tests, lint, isolated signed APK, byte-identical
Official assets and actual Android API 35 native GPS replay. Both locations use
the original captured binary vector tiles with matching before/after camera/GPS;
native route-blue pixels and geometry invariants are checked. Zoom, rotation,
day/night, GPS updates, camera animation and offline cached style reload are checked.
Existing lane/Then, History and ERP native regressions are retained.

The testing replay uses recorded real map/route data and actual Android rendering.
It is not a physical road test, complete offline Singapore map, or phone FPS benchmark.
No Official merge, stable release update or production data migration is authorized.
