# B889 Official status stamps

The user approved the B889 TESTING APK and explicitly requested its Official merge.
Approved APK SHA-256: `2e6279d8e3e824f22c65e41eb6326b04be8981128ac6b17effd31d902eb91a7b`.
Baseline: B888 Official, commit `d4982e678c7a4bfdaaead8570eabd73543fba321`.

The live Route, History delivery cards and delivery report use the three exact
supplied PNGs in `app/src/main/assets/status_stamps`. Card/report measurements and
surrounding layout are preserved. All 18 approved rendering hashes must match.

The release workflow first validates the candidate branch. Publishing is restricted
to main, after tests, lint, release build, asset checks and original-key signing.
The canonical stable version is `v1.4.78-b889`, package
`com.izzyan.sgdeliveryplanner`, versionCode 98. B888 remains latest until the
verified signed B889 APK is successfully published.

Restore the 436 exact B888 application assets before a local build:

```sh
python3 scripts/verify_b889_official.py restore /absolute/path/SG-Delivery-B888-Official.apk
./gradlew testDebugUnitTest lintDebug assembleRelease
python3 scripts/verify_b889_official.py verify /absolute/path/SG-Delivery-B888-Official.apk app/build/outputs/apk/release/app-release-unsigned.apk /absolute/path/verification
```

Only three status graphics and their rendering are changed in production code.
Official data, settings and navigation behaviour are preserved.
