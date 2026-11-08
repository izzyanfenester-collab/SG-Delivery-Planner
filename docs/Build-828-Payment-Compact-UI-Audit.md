# SG Delivery — Build #828 Payment Compact UI audit

## Baseline and scope
- **Source baseline:** Build #827 Testing (commit `3584e989e435b25464bf46d5e1d3f7582d54fb3e`).
- **Change scope:** `Payment.kt` composable presentation only, `app/build.gradle.kts` testing version, and a Payment-only unit test. No changes to Home, Route, Delivery, Map, History, Settings, GPS or report renderers.
- Official Build #823 in `main` and Build #827 testing branch are not modified.

## Before-and-after implementation audit
These are **source token / minimum component dimensions**, not measured rendered device screenshot heights.

| Component | Build #827 before | Build #828 after |
| --- | --- | --- |
| Payment screen | 16dp horizontal, 12dp vertical, 12dp section gap | 12dp horizontal, 6dp vertical, 10dp section gap |
| Top of Payment page | Separate SGPageHeader plus Payment overview | Single Payment overview row, no duplicate title |
| Overview card | Column with subtitle and date pill, 12dp vertical padding | Single line 56dp minimum with date on right, 8dp vertical padding |
| Calculator container | SGPaymentCard compact: 12dp padding, 8dp child gap | Payment-only container: 10dp padding, 6dp child gap |
| Calculator heading | 22sp + 24dp icon, decorative border/padding | 18sp + 19dp icon, 40dp minimum |
| Field row | 40dp label group + 3dp outer padding; decorative vertical stripe | 48dp min responsive row, no decorative stripe |
| Editable input | 7dp vertical padding, no minimum box height | 42dp minimum box, 5dp vertical padding |
| Grand Total | Same generic row shape/size | Compact 56dp minimum with gold border, gold amount |
| Reset / Save | Shared row, 48dp minimum, 12dp gap | Same actions, 48dp minimum, 8dp gap, 12dp radius |
| History | 50dp row slot, 56dp header, 16dp body font, 14dp header | 48dp minimum row slot, compact header, 14dp body / 12dp header |
| Monthly Summary | Separate title, paragraph and year row | Heading and year share one row, compact table |
| History & Monthly amounts | Fixed three columns, right align through per-cell boxes | Same three columns, directly right-aligned text |

### Estimated vertical savings from structural changes
- Removed one redundant title/subtitle header (reclaims a whole rendered line group).
- Collapsed two stacked overview texts and padded date pill into one row.
- Reduced calculator card child spacing and decorative heading.
- Merged History heading/help into one row.
- Merged Monthly heading/year into one row, removed redundant paragraph.
- Reduced table title/header and row spacing.

The requested **25–35% reduction is a design target**. A numeric rendered-page percentage, viewport fit and exact before/after screenshot cannot be claimed without matching Android UI measurements at the same viewport/density/font scale.

## Preservation verification
- The PaymentRecord, PaymentCalculation, PaymentLedger and payment formulas section are byte-identical to Build #827.
- PaymentEditDialog code is unchanged.
- History/month selection, long-press record edit/delete, confirmation dialogs are unchanged.
- No payment database schema, backup/restore or source record is modified.
- Payment Page still uses shared Navy `#081B33`, Gold `#D4AF37` and Marina Bay Sands backing from the existing global shell.

## Test checklist
- JVM/Android local unit tests including `PaymentTest`, `PaymentCompactUiTest`, and current app regression suite.
- `assembleDebug` + `lint` required.
- **Pending actual Android device capture:** calculator dimensions, 360dp small phone, large phone, 100%, 130%, 160%, 200% font scale, keyboard raised, five-plus payment records scrolling, long-press edit/delete, monthly totals, restore and dark artwork contrast.
- Build #828 remains isolated under `.testing` and must not be merged without visual review.
