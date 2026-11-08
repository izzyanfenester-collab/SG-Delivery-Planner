# SG Delivery — Build #827 UI Consistency Audit & Design Implementation

**Baseline:** Official `main` Build #823 (`1.4.12-b823`)  
**Development branch:** `feature/build-827-unified-design-system`  
**Status:** Source changes for testing; NOT official; does not change saved order, routing or payment schemas.

## 1. Baseline audit — before

| Screen/module | Inconsistency verified in #823 source | Unification applied in #827 |
| --- | --- | --- |
| App theme | Core Navy `#07172D`, Gold `#E4BC69`, separate light palette; oversized 18–28dp card radii | One dark-only `SGColors` palette with specified `#081B33` / `#D4AF37`, 12/16/20dp radii |
| Home | Standalone outlined postal form with custom 17dp radius, custom small actions, old card defaults | `SGTextField` for postal input, `SGPrimaryButton` / `SGSecondaryButton`, shared cards and typography |
| Route | Different CTA/button and individual stop-card treatment | Shared `SGDeliveryCard`, `SGPageHeader`, status badge, gold primary actions |
| Delivery | Status buttons, postal display, proofs and route card had ad-hoc shapes | Shared typography, delivery card and status tokens; all status-specific actions preserved |
| Map (route overview) | Inherited primary controls with old palette | Global Material theme and navigation controls standardized; map geometry untouched |
| Bottom navigation | Custom icon-only, 34dp icons, royal-blue selection, 18dp pill | Shared `SGBottomNavigation`; same Material Outlined icon family, gold active state, labels, 24dp icons |
| History | V6-only blues `#06172A/#0B2949/#12365D`, separate gold `#FFC76D`, 30sp title | V6 palette aliases use global tokens; 22sp page title, consistent card radius/outline and spacing; stamps untouched |
| Payment | 21 ad-hoc hex shades, italic 26sp section headers, custom 22dp panels and decorative text glyphs | SG section headers, payment cards, shared colors and consistent table alignment; payment ledger logic untouched |
| Start/End location | Standalone 56dp gold/outlined controls, muted text mismatch | Shared buttons and Silver Grey labels; geocoding and selection logic unchanged |
| WhatsApp import | Generic cards and standalone blue/green/warning accents | Shared `SGCard` and status accents; original order parsing and messaging untouched |
| Settings | Compact sections but inconsistent left margin/button treatment | 16dp screen margins, shared cards, buttons, icon family and Material selection colors |
| GPS built-in | Separate navy/gold literal values and corner sizes across overlays | Unified GPS chrome, 16dp panels/12dp control corners and 28sp maneuver distance, while retaining specialized map UI |
| GPS petrol/traffic dialogs | Different muted/warning shades and modal backgrounds | Shared Navy/Gold/Silver/Warning palette |
| Delivery summary preview | Independent 26dp modal, pale success/warning colors, bespoke semi-transparent surface | Global modal radius, card surface and functional status colors |
| Summary and single-delivery PNGs | Report-only RGB gold/white/navy constants; separate panel tint | Shared `SGReportColors`; retained original Marina Bay, proof, COD/PayNow images and layout |
| Schedule JPEG | Light/white rows and dark text unrelated to app | Dark Navy table, Gold header, White text and muted dividers without changing cell data |
| Marina Bay backdrop | Theme-dependent white or dark scrim; report uses different RGB | Centralized dark-only background overlay; keep same image placement and crop |
| Dialogs and popups | Some special custom colors even with Material defaults | Global Material dialog palette and reusable `SGDialog` for future calls; selected explicit dialogs use tokens |

### Retained specialized semantics

- Green: delivered/success, and reliable smooth traffic only.
- Yellow: on-hold/warning and reliable slow traffic only.
- Red: error/failed and reliable jammed traffic only.
- Blue: current GPS path; grey: traveled route; road overlay rendering code unchanged.
- Existing COD and PayNow stamp assets and **35-degree clockwise orientation** are retained exactly.
- Full-screen History proof viewers, report thumbnails, Marina Bay image, and branded ChatGPT icon are retained.
- Tab order: Home / Route / Delivery / Map / History / Payment, unchanged.

## 2. Shared design tokens

| Category | Canonical value |
| --- | --- |
| Main background | `#081B33` |
| Secondary background | `#102A4C` |
| Card | `#152F50` |
| Gold | `#D4AF37` |
| Selected Gold | `#F1D57A` |
| White | `#FFFFFF` |
| Silver | `#D6DEE8` |
| Divider | `#29415D` |
| Disabled | `#728096` |
| Spacing | 4 / 8 / 12 / 16 / 24 dp |
| Card radius | 16dp |
| Buttons and inputs | 12dp radius |
| Dialog radius | 20dp |
| Minimum touch target | 48dp |
| Main title | 22sp bold |
| Section header | 18sp semibold |
| Content | 15–16sp |
| Supporting labels | 12–13sp |
| Icons | 24dp |
| Font | Android sans-serif/Roboto fallback; Android font-scale aware |

### Reusable components

`SGColors`, `SGDimensions`, `SGPrimaryButton`, `SGSecondaryButton`,
`SGIconButton`, `SGCard`, `SGPageHeader`, `SGTextField`,
`SGStatusBadge`, `SGDialog`, `SGSectionHeader`,
`SGBottomNavigation`, `SGDeliveryCard`, `SGPaymentCard`,
`SGReportColors`.

Old `PremiumNavy`, `PremiumRoyal`, `PremiumGold`, `PremiumCard`
names remain as source compatibility aliases but resolve to the new central system.

## 3. Business-data and navigation preservation

The change is scoped to Compose presentation layers, colors, typography, shapes,
shared renderer palette, and independent regression tests.

Explicitly not altered:

- `Planner.kt`, `Data.kt`, `DeliveryState.kt`, `PlannerViewModel.kt` (route planning, histories, progress).
- `NavigationRouting.kt`, `NavigationMap.kt`, `LocalNavigationTraffic.kt` (actual routing/camera/traffic logic).
- `HistoryBackup.kt`, `PaymentLedger` model/logic, delivery/status serialization.
- No manifest permission or application ID changes to the production app.
- Launcher name remains **SG Delivery**.

Debug testing builds keep the existing `.testing` package suffix, so installation is separate from Official.

## 4. Honest baseline functionality boundary

Build #823 has built-in MapLibre/Valhalla navigation and **local, phone-only GPS traffic estimates**. It does **not** contain a deployed shared-runner live traffic service or true Singapore offline road routing. #827 preserves the existing behavior; UI standardization **does not add** those absent capabilities or claim that they are live.

True 3D vehicle model and some advanced GPS features also require independent on-device validation rather than a visual-only claim.

## 5. QA and screenshots

Automated scope:
- Existing Android unit suite, `assembleDebug`, `lint` via GitHub Actions.
- Exact palette token and shared Canvas palette unit tests.
- Existing import, delivery, GPS routing, payment, History, and report tests.

**Actual screenshot requirement is pending on-device capture**. Android UI screenshots cannot be honestly generated from source review alone. The following before/after pairs should be captured from Build #823 Official versus #827 Testing at the same Android device resolution and font scale:

1. Home, including postal list and Start/End.
2. Route, including mixed COD/PayNow stops.
3. Delivery, with proof and status stamps.
4. Route Map overview.
5. Built-in GPS (portrait, landscape, 2D/3D, nav/ETA panels).
6. History list and History read-only detail.
7. Payment, calculator, history and monthly totals.
8. Settings (all subsections).
9. WhatsApp import preview/errors.
10. Start/End location picker.
11. Backup/Restore, deletion/confirmation and other dialogs.
12. Delivery Summary and report viewer.
13. Shared single-delivery PNG / summary PNG / schedule JPEG.

Acceptance on actual phones:
- 360dp small screen, normal and large screens; 100%, 130%, 160%, 200% font scales.
- Portrait and supported landscape; keyboard raised, accessibility, bottom/system insets.
- No clipped tabs, texts, buttons or tables; disabled/loading/selected states readable.
- 30-stop route, duplicate postal orders, proof, COD, PayNow, History, Payment, restore.
- Verify route geometry, GPS overlays and ETA do not change when visually switching themes.
- Before/after screenshots reviewed by the owner **before** official merge.

**Do not merge into main until screenshot/device QA and owner review are complete.**
