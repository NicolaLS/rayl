# Maestro flows

UI flows for Android and iOS from the same files (qa/test-plan.md §6.8). They drive the E2E app
(`com.nicolasusca.rayl.e2e`) against the local wallet stacks in `tools/`, so they need no funds
and no real wallet.

The E2E app never uses the camera: a `scan` launch argument is what the payment screen reads,
once (`TestQrInput` in `core/camera`). A flow that pays connects a wallet first, then relaunches
the app with `scan` set to the invoice.

```sh
tools/nwc-stack/bin/up
./gradlew :app:androidApp:assembleE2e
adb install -r app/androidApp/build/outputs/apk/e2e/androidApp-e2e.apk
maestro test -e APP_ID=com.nicolasusca.rayl.e2e e2e/flows/nwc
```

`scripts/*.js` talk to the stacks' helpers on the host (Maestro runs there too) for connection
URIs, invoices and what the receiver got. The receiver's node is the oracle for every payment;
the screen is checked too, but never trusted alone.

Onboarding sets up Rayl's wallet with Breez (mainnet, no funds), which needs the internet and
can take a minute.
