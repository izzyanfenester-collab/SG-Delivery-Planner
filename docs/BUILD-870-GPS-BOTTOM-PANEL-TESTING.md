# SG Delivery #870 TESTING — High-Visibility GPS Destination Bottom Panel

## Source baseline and isolation
Built from latest verified GitHub \`main\` B869 Official, starting SHA \`00cf273e26d56d8b94423f2e75f3019e2e67601f\`. Isolated Android \`com.izzyan.sgdeliveryplanner.b870test\` debug build, version \`1.4.58-b870-bottom-panel-testing\`. No changes to Official branch or stable release.

## Scope: bottom navigation panel, and trustworthy destination field only
- The current stop's actual \`Place.block\` is passed into \`builtInNavigationIntent()\` alongside existing postal, address and GPS coordinates, then retained in the in-navigation destination snapshot. The postal always comes directly from that same stop. Navigating to the next stop naturally passes a different snapshot.
- A block number is accepted only if it is a valid structured numeric/letter block value (e.g. \`11\`, \`123A\`) or explicitly marked \`Blk 11\` / \`Block 11\` in an address. Raw street numbers such as \`11 Bedok North Road\` are **not** assumed to be block numbers. If unknown, display the verified six-digit postal only, or actual destination name if postal absent. Original postal leading zeroes remain unchanged.
- Display \`Block 11 (460011)\` as a single line wherever measured text width fits; otherwise show \`Block 11\` and \`(460011)\` on separate full-size lines. Each is **25sp ExtraBold** in Navy/Gold Official's bright gold accent; never shrink/ellipsize postal numbers. At high system font scales the panel naturally expands.
- Keep \`ARRIVAL\` 13sp bold, ETA 29sp ExtraBold, remaining distance and duration 17sp bold with wrapping if needed, and original \`finish()\` click for \`← Delivery\` with a minimum 48dp touch target. Preserve local traffic text and dynamic panel-height measurement (map insets use the actual rendered panel height).
- Official baseline supports the existing Navy & Gold dark theme; B870 does not introduce Burgundy/Racing Red variants or alter global theme selectors.

## Verified unchanged
Do not change GPS/routing/ETA calculation, camera location datasets, camera voice detection, Panda Warrior audio engine or 209 authentic recordings, advanced zoom, red/blue/grey route, offline maps, delivery workflows, payment, history, tax, backups or route data. Camera database still 341 SPF records and 100 user-provided speed reference coordinates. All 209 original MP3s are byte checked by the TESTING GitHub workflow.

## Acceptance
- Unit tests: numeric and prefixed blocks, alphanumeric blocks, six-digit leading-zero postal, no made-up block from street number, verified explicitly labelled address, missing block fallback, updated next-stop snapshot, minimum UI font/Delivery touch-area contract.
- CI: Android unit tests, lint and APK signing/asset verification with real Panda audio, separate testing prerelease.
- **Still requires manual Android checks:** 320–430dp displays, accessibility font scales, long block labels, screen rotation, real next-stop progression and Delivery button behavior. CI success alone is not pixel/speaker/human driving certification.

**Never merge to Official without explicit user approval.**
