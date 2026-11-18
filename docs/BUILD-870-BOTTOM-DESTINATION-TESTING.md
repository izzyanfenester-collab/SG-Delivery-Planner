# SG Delivery #870 TESTING – Built-in GPS high-visibility bottom destination panel

Baseline: latest verified Official Build **#869**, commit `00cf273e26d56d8b94423f2e75f3019e2e67601f`. **No Official merge.**

## What changed
- GPS destination Intent now carries **the actual saved Place.block**, along with the already carried postal, address, lat/lon. No destination or delivery-state changes.
- Responsive full-width heading: `ARRIVAL` 13sp bold and authentic `Block 11 (460011)` 26sp ExtraBold, champagne gold. The actual pixel width is measured at Android font scale; when necessary, labels stack and block/postal split onto **two readable 26sp lines** without truncating or shrinking to tiny type.
- If block absent or unrecognizable, show saved six-digit postal only. An explicit `Blk/Block 11` may be extracted from saved address; a street house number is **never guessed** as an HDB block.
- ETA remains live at 29sp ExtraBold; distance/duration at 17sp Bold with wrapping if needed; Delivery button retains existing finish/back navigation callback, with 48dp minimum touch height.
- Original solid navy/gold theme retained. B869 Official has no native Burgundy/Silver or Racing Red/Pearl White GPS panel theme, so no new theme was fabricated.
- All existing GPS routing, 3D arrow, zoom, rerouting, speed limits, offline map, the 209 original ElevenLabs Panda Warrior voice clips, 341 camera records, saved data and all delivery/payment functions unchanged.
- Separate debug APK `com.izzyan.sgdeliveryplanner.b870test`, version `1.4.58-b870-testing`. Official application data is untouched, and TESTING does not share it automatically.

## Checks
Robolectric tests verify real block/postal formatting, no fabricated street numbers, invalid postal fallback, width layout decision, destination switch, and Intent `block` snapshot. CI also tests/lints/builds Android, checks the source-scope, 209 original MP3s and SPF camera snapshot/coordinate records, and publishes only a GitHub **prerelease** after success.

Still requires on-device visual checks at 360dp / 320dp widths, 1.3x / 1.5x system font scales, long block numbers and large accessibility fonts, as well as tapping the original Delivery button.

**Do not merge into Official without explicit user approval.**
