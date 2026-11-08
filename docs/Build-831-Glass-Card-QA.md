# SG Delivery Build #831 – 70%-transparent Navy card QA (base: Official #829)

## Scope and non-negotiable alpha semantics
- Source base: Official `main` version 1.4.18-b829.
- Next isolated testing variant: versionName `1.4.19-b831-glass30-testing`, versionCode 30, debug package suffix `.glass30test`.
- **70% transparent = 30% opaque**, only for the **card's background paint**; no opacity change to child text, icons, logos, photos, stamps or action controls.
- Central Compose token: `SG_CARD_BACKGROUND_ALPHA = 0.30f`; card color is `SGColors.Background.copy(alpha = SG_CARD_BACKGROUND_ALPHA)`, Navy RGB `#081B33`.
- Canvas reports share this very token through `SGReportColors.ReportPanel`, expected `#4D081B33`.
- Never set `Modifier.alpha` or `View.alpha` on whole card containers.
- Do not stack separately translucent fills in nested Payment rows, Payment tables, History statistics or History proof thumbnails; those children draw borders on the parent's 30%-opaque Navy.
- Text fields, safety warnings, dialogs and primary action buttons can deliberately retain a high-contrast solid fill.

## Static source audit of targeted surfaces
| Page/feature | Covered card layers | Status for source review |
|---|---|---|
| Home | Common PremiumCard/SGCard, start/end location cards | Implemented |
| Route | Optimized stop cards, overview cards, schedule table rows | Implemented |
| Delivery | Active stop, other stops, status overview | Implemented |
| Map | Built-in GPS header, speed readout, arrival/ETA overlay | Implemented, map canvas remains untouched |
| History | Saved route cards, statistics, route detail, proof image frames, date/header glass | Implemented; status badges/photos not dimmed |
| Payment | Overview, calculator panel, calculator rows, Grand Total, history, monthly summary, table rows | Implemented with nested fills disabled |
| Settings | Shared PremiumCard | Implemented |
| Delivery Report PNG | Existing 1080px generated bitmap panel | Uses the unified 30%-opaque Navy ARGB token |
| Delivery Summary PNG | Existing 1080px generated bitmap panel | Uses the same unified 30%-opaque Navy ARGB token |
| Popup/dialogs | Standard SGDialog, FuelStationDialog | Deliberate solid dialogs retained for contrast |
| Marina Bay Sands | Original `marina_bay_sands_dark` asset and crop/position untouched | Scrim reduced to 42% to improve backdrop visibility |
| Navigation controls | MapLibre road map, route line, marker, navigation buttons | Left untouched; only information cards changed |

## Required tests before claiming visual release readiness
These are **manual Android-device tests**, not verified by a Kotlin compilation or GitHub workflow. All must be marked individually after actual in-app inspection and before/after screenshots:

- [ ] Home – background visible under start/end/postal cards; inputs and buttons legible.
- [ ] Route – route rows and export, no dark overlapping table layers.
- [ ] Delivery – text, COD/PayNow 35-degree stamps, delivery actions, proof photos fully opaque.
- [ ] Map – actual map fully opaque, route line and vehicle markers unaffected; overlay text legible on both bright and dark roads.
- [ ] History – card surfaces 30% opaque with transparent nested proof/status frames; saved delivery record intact.
- [ ] Payment – compact layout, wrapped headings, centered amounts, editable tax/Others, reset/save, 5-row history scroll, long-press edit/delete, monthly totals.
- [ ] Settings – card opacity and navigation.
- [ ] Delivery Report – generated PNG has original MBS art, 30%-opaque panel, legible details, full-size photos/logos.
- [ ] Delivery Summary – generated PNG has original MBS art, 30%-opaque panel and readable figures.
- [ ] Any Payment Summary share/export path available in current build – inspect actual output.
- [ ] Portrait and landscape / font-size variations.
- [ ] Existing saved orders, payments, route statuses and backup/restore unaffected.
- [ ] Screenshot pair for all applicable pages **captured from running Official #829 versus Build #831 on Android**.

## Automation scope
- GitHub Actions: `testDebugUnitTest`, `assembleDebug`, `lintDebug`; these verify compilation, unit tests, and static lint only.
- Existing regression tests cover Payment arithmetic, centered table headers, reports and routing/navigation logic without changing their data/business implementation.
- CI artifacts are **testing APKs only**. They must not be promoted as an Official signed APK before actual-device visual and functional checks.
- Offline maps with full networkless routing/30-stop optimization are **not implemented by Official #829**. This visual-only change does not implement or claim to validate those unrelated features.
