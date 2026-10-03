# IZZ Delivery shared business logic

Standalone Kotlin Multiplatform build using Kotlin 2.2.21 and Gradle 8.13. It is deliberately independent of the existing Android root build, which remains unchanged.

```sh
./gradlew -p shared jvmTest
# On macOS, Xcode runs this with its SDK/configuration environment:
./gradlew -p shared embedAndSignAppleFrameworkForXcode
```

Targets: JVM, iosArm64, iosSimulatorArm64, iosX64. Framework: static `IZZShared`. `DeliveryEngine` accepts/returns JSON to simplify Swift bridging; common models and pure functions are also reusable from Kotlin. All business timestamps are Unix seconds; Swift formats them in Asia/Singapore. Shared functions never call Android APIs, mapping services, UI, filesystem or a device clock.

The domain port preserves Android's directed full-tour cost evaluation, traffic bands, service timing, returned-leg semantics, reviewed-pending distinction, delivery timestamp clearing and manual independent SGD values. Common tests exercise invalid/duplicate postal tokens, directed return costs, traffic bands, timing/rounding, status changes, saved-summary invalidation and exact cents. Swift integration tests exercise the framework, protected persistence and independently readable Excel files.

Android's existing models continue to be the Android source of truth in this branch. A later Android adoption must explicitly adapt java.time/Gson/Room payloads and run the existing regression tests; this work does not silently change Android persistence or behavior.
