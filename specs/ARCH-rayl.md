# ARCH-rayl: Rayl application

The [Gradle project](../settings.gradle.kts) builds Rayl for Android and iOS.
Rayl is the only distributed app. Product composition lives in `app/`;
independent Rayl, Blink, Nostr Wallet Connect (NWC), and Spark implementations
live in `providers/`; only the Rayl provider, Rayl's built-in wallet, needs no
credentials.

Rayl pays with its built-in wallet, the Rayl provider's pocket, by default: after
the welcome it mounts and asks only for its terms and a passkey. Its Settings offer every external provider
under "Use another wallet", with no release availability gates, and an external
wallet's Settings offer "Back to Rayl".
[RaylSelection](../app/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylSelection.kt)
keeps external setup transient and remembers only a connected external wallet;
it does not combine wallets into one payment engine. Before mounting any wallet,
the platform host clears unowned external provider storage. Going back to Rayl
also requires cleanup, as defined by [SPEC-wallet-lifecycle](SPEC-wallet-lifecycle.md).

## Shared boundaries

Rayl supplies product configuration and platform entry points. Provider modules
own wallet connection, credentials, and payment execution, as described in
[ARCH-providers](../providers/specs/ARCH-providers.md).
The root `core/`, `feature/`, and `integration/` modules supply provider-neutral
models, platform services, reusable UI, payment-input utilities, and service
adapters. Shared payment presentation does not own settlement.

[ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md) describes
the shared Hub UI.

`backend/rayl` is the Rayl backend, deployed on Railway. It serves bitcoin
prices aggregated from several public sources, and every provider reads fiat
prices from it through `integration/exchange-rate`. `core/rayl-api` is its wire
contract and holds the production base URL.

The dependency rules in [`verifyModuleDependencies`](../build.gradle.kts)
keep providers independent of one another and of the app, and neutral modules
independent of the app and providers. Backend modules share only their wire
contracts (`core/rayl-api`) with client code; clients do not
depend on backend implementation.

## Platform composition

Common Kotlin owns application state and payment logic. Android renders with
Compose; iOS renders with SwiftUI through Kotlin controllers and snapshots
exported by Rayl's `Shared` framework. Reusable native views live beside
their feature or provider, with Rayl connecting platform lifecycle and
incoming links to the selected experience.
