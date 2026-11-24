# Build 886 TESTING — soft-coloured transparent native 3D buildings

Exact baseline: B885 Official, c9c824b97de50465a2a4f60b7be0522761b3d155.
Next unused build 886, version 94 / 1.4.74-b886-testing, isolated Android package
com.izzyan.sgdeliveryplanner.b886test. This build does not replace the installed
Official app, migrate its database or change the stable update feed.

Production changes are restricted to NavigationBuildings886.kt and one additional
FillExtrusionLayer case in the existing NavigationMapPalette.apply entry point.
Only building extrusion colour and fill-extrusion-opacity are changed. Six muted
colours use native data expressions seeded by source feature ID, then actual
osm_id/id/render_height when a numeric source ID is unavailable. Completely
unidentified features use one stable colour. Colours are styling only, without
claims about actual building use. Day and night use corresponding restrained
palettes. Opacity is 0.55. The SDK's existing extrusion lighting remains active.

No route geometry, coordinates, blue colour, width, opacity, casing, annotation
order, vehicle arrow, building filter, height/base, footprint, zoom visibility,
source, camera code, preferences or application data schema is changed. The
existing native navigation layers continue to retain their authored colours.
Existing style-ready and day/night callbacks apply the paints in place. GPS
updates do not re-create building layers, reload sources or restart animation.
There are no new production dependencies, network requests or paid services.

Native evidence uses Android API 35 / MapLibre 13.5.0 with SwiftShader. The
non-exported DEBUG-only replay host renders actual OpenFreeMap/OSM Woodlands
geometry captured on 24 November 2026, including stored heights 4–121 metres.
All original building properties, feature IDs, coordinates and complete polygons
are retained. A captured Valhalla driving route uses Rosewood Drive, Woodlands
Avenue 1, Woodlands Avenue 2, Woodlands Avenue 5 and Woodlands Drive 50.
Geographic samples feed the production GPS matcher/progress tracker and original
instruction/lane/Then UI. Route lines use the original native annotation API,
RGB colours and widths. No AI-generated images are used.

The local test fixture adapts original Liberty layer source references to
captured GeoJSON, with the original building height/base/minzoom/filter/lighting
and 0.8 gray appearance before styling. Road labels use actual Latin names and
bundled Noto Sans glyphs; this fixture is not a complete Singapore basemap. The
production style URL and all offline storage behaviour remain unchanged.
The source manifest records tile/route hashes, source links and attribution.

Paired BEFORE/AFTER screenshots use identical GPS, route, zoom, pitch, bearing
and map appearance, in both day/night modes and two bearings around tall and
dense Woodlands buildings. Native map snapshots compare the exact blue route
core against a buildings-hidden depth control, to test actual rendering rather
than assuming ordering alone. Building geometry/height expressions, route
coordinates/colours/widths and camera position must compare equal. The control
images are test-only, not a production building-hiding feature.

Further native checks cover 2D/3D, wide/near zoom, rotation, GPS updates, local
style reload, stable colour expressions, persistent layers/sources and a real
1800ms camera animation while paints change. Original B885 lane/Then, B884
History and B876 ERP native regression tests run without altering assertions.
All baseline JVM/Robolectric tests plus five building-policy tests run, with
lint, APK package/signature/16KB ZIP alignment checks and byte comparison of all
436 original application assets (209 MP3 and 209 PCM clips, camera/road/postal
data, and ERP 106 records / 101 locations).

Limits: emulator GPU/replay verification is not a physical road drive, phone
frame-rate benchmark or new Bluetooth/audio acceptance. Full Singapore building
coverage is not claimed. Styling works on existing cached/local building tiles;
the baseline's ability to obtain a fresh complete offline native basemap is not
expanded. Existing saved Official data remains in the separate Official app.
Only the premium themes/map modes actually available in B885 are retained; the
native navigation panel uses its existing Navy & Gold palette.

NO OFFICIAL MERGE. No stable release/update-feed changes.
