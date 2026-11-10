# Rayl

Rayl is a Kotlin Multiplatform Lightning app for Android and iOS. Pay from a Rayl
pocket that needs no setup, or connect a Blink account with an API key, a
compatible wallet through Nostr Wallet Connect, or a Spark wallet with its
recovery phrase. Rayl uses one provider experience at a
time and is the repository's only distributed app. It is prerelease.

See [Rayl architecture](specs/ARCH-rayl.md) for application composition and
[provider ownership](providers/specs/ARCH-providers.md) for the independent wallet
implementations.

## Project layout

- `app`: Rayl composition, platform entry points, and wallet selection
- `providers/rayl`, `providers/blink`, `providers/nwc`, `providers/spark`: independent provider implementations
- `core/*`: provider-neutral models, platform services, and UI primitives; `core/telemetry` holds the trace and the anonymous usage data
- `feature/*`: reusable provider-neutral user stories
- `integration/*`: shared external-service adapters
- `distribution/*`: distribution artifacts
- `tools/*`: developer tools, such as the [regtest bank](tools/regtest/README.md) for manual QA

## Build

Use JDK 21 and the root Gradle wrapper.

For upload-key setup and Google Play releases, see [Distribution](distribution/README.md).

Debug builds come in two apps that install side by side: **Rayl Dev** on Lightspark's
Regtest (no key; see the [regtest bank](tools/regtest/README.md)) and **Rayl Dev
Mainnet** on real bitcoin (`./gradlew :app:androidApp:installDebugMainnet`, or the
`iosApp Mainnet` Xcode scheme). Release builds always use Mainnet. Mainnet builds need
`RAYL_BREEZ_API_KEY` in the environment; load it from your secret manager in the
gitignored `.envrc.local` (see `.envrc.example`). Xcode does not see shell variables,
so have `.envrc` run `scripts/xcode-secrets`, which mirrors the key into the gitignored
`app/iosApp/Configuration/Secrets.xcconfig` whenever direnv loads.

Debug builds log payment-path timings (milliseconds since launch) under the `RaylTiming`
tag: `adb logcat -s RaylTiming` on Android, or filter Console.app or the Xcode console
for `RaylTiming` on iOS. The same marks are trace sections (`camera.*`, `payment.*`) in
Perfetto and Instruments.

The store app sends anonymous usage data to the Rayl backend unless the user turns it off
in Settings. [`TelemetryCatalog.kt`](core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt)
lists everything it can contain. Debug builds log each event under `RaylTelemetry` instead
of sending it (`adb logcat -s RaylTelemetry`); E2E and benchmark builds keep nothing.

```shell
./gradlew :app:androidApp:assembleDebug
./gradlew :app:androidApp:assembleE2e
./gradlew check
```

Run startup and camera benchmarks on a connected Android 10+ device with the
separate benchmark app. Use a stable physical device for comparable results:

```shell
./gradlew perfCheck
```

On macOS, open `app/iosApp/iosApp.xcodeproj`, or build the complete Kotlin
and Swift app for the iOS simulator as in [iOS CI](.github/workflows/ios.yml):

```shell
xcodebuild \
  -project app/iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "${TMPDIR:-/tmp}/rayl-ios" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO \
  build
```
