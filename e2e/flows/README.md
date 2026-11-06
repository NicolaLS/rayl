# Maestro flows

UI flows for Android and iOS from the same files (qa/test-plan.md §6.8). They drive the E2E app
(`com.nicolasusca.rayl.e2e`) against the local wallet stacks in `tools/`, so they need no funds
and no real wallet.

The E2E app never uses the camera: a `scan` launch argument is what the payment screen reads,
once (`TestQrInput` in `core/camera`). A flow that pays connects a wallet first, then relaunches
the app with `scan` set to the invoice.

The E2E app also talks to the local Blink stack instead of Blink's API (`TestBlinkApi`;
`blinkApiUrl` launch argument, by default the stack's address as the emulator or simulator sees
it). Every other build uses the camera and Blink's API as before.

```sh
tools/nwc-stack/bin/up && tools/blink-stack/bin/up
./gradlew :app:androidApp:assembleE2e
adb install -r app/androidApp/build/outputs/apk/e2e/androidApp-e2e.apk
maestro test -e APP_ID=com.nicolasusca.rayl.e2e e2e/flows        # all flows (config.yaml)
```

| Flow | Checks |
|---|---|
| `nwc/pay-and-rescan` | Smoke S3, S4, S7: paid below the Auto Pay threshold; a rescan after a restart pays nothing more |
| `nwc/confirm-above-threshold` | S6: confirmation above the threshold; dismissing pays nothing |
| `nwc/expired-invoice` | An expired invoice is refused before the wallet; nothing paid |
| `blink/pay` | S8: the receiver gets the amount; Blink's balance drops by amount + the fee shown |
| `blink/amountless-invoice` | The amount entered is what arrives (not NWC: MOB-115) |
| `blink/insufficient-balance` | An empty wallet: a clear message, nothing paid |
| `wallet/back-to-rayl` | S9: back to Rayl's wallet, also after a restart; the NWC credentials are gone |
| `settings/language-german` | S10: German texts and amount format through a payment |
| `spark/corrupt-credential` | iOS Keychain: an unreadable Spark phrase is explained; removal asks, then starts over |
| `spark/orphan-credential` | iOS Keychain: a leftover Spark phrase is never restored by itself |

The two `spark/` flows seed the iOS Keychain (`sparkFixture` launch argument, iosAppE2E only) and
do nothing on Android. On iOS (simulator signing is needed: unsigned apps can't use the
Keychain):

```sh
xcodebuild -project app/iosApp/iosApp.xcodeproj -scheme iosAppE2E -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/rayl-ios-e2e \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build
xcrun simctl install "$SIMULATOR" "/tmp/rayl-ios-e2e/Build/Products/Debug-iphonesimulator/Rayl E2E.app"
maestro --device "$SIMULATOR" test -e APP_ID=com.nicolasusca.rayl.e2e e2e/flows
```

If the link fails with "Unknown dependent library io.github.nicolals:nostr-core", delete
`app/shared/build/kotlin-native-ic-cache` (a cache from before `nostr/` was built from source).

`scripts/*.js` talk to the stacks' helpers on the host (Maestro runs there too) for connection
URIs, invoices and what the receiver got. The receiver's node is the oracle for every payment;
the screen is checked too, but never trusted alone.

Onboarding sets up Rayl's wallet with Breez (mainnet, no funds), which needs the internet and
can take a minute.
