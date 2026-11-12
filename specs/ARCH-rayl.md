# ARCH-rayl: Rayl application

The [Gradle project](../settings.gradle.kts) builds Rayl for Android and iOS.
Rayl is the only distributed app. Product composition lives in `app/`;
independent Rayl, Blink, Nostr Wallet Connect (NWC), and Spark implementations
live in `providers/`; only the Rayl provider, Rayl's built-in wallet, needs no
credentials.

Rayl pays with its built-in wallet, the Rayl provider's pocket, by default: after
the welcome it mounts and asks only for its terms and, where the device and
password manager support it, a passkey. Besides the pocket, the user adds cards:
any number of external wallets, several of one provider included, with no
release availability gates. Every wallet's Settings start with the shared wallet
block from `feature/wallet-management`, the same for every wallet: the active
wallet, which opens Wallets to pick another or add a card; its balance with Add
funds (only the pocket has them so far); and Details, the active wallet's page with
its own settings and Remove card. Wallets lists the pocket like any card, first.
[RaylCards](../app/shared/src/commonMain/kotlin/xyz/lilsus/rayl/RaylCards.kt)
owns the cards, which one pays, and per-card storage names; it keeps setup
transient and mounts exactly one wallet at a time, without combining wallets into
one payment engine. Before mounting any wallet, the platform host erases storage
no card owns, as defined by [SPEC-wallet-lifecycle](SPEC-wallet-lifecycle.md).

## Shared boundaries

Rayl supplies product configuration and platform entry points. Provider modules
own wallet connection, credentials, and payment execution, as described in
[ARCH-providers](../providers/specs/ARCH-providers.md).
The root `core/`, `feature/`, and `integration/` modules supply provider-neutral
models, platform services, reusable UI, payment-input utilities, and service
adapters. Shared payment presentation does not own settlement.

`nostr/` holds the Nostr SDK behind the NWC provider. It is a self-contained
library: it depends on no other Rayl module, so it can be split out again with
its history.

[ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md) describes
the shared Hub UI.

`backend/rayl` is the Rayl backend, deployed on Railway. It serves bitcoin
prices aggregated from several public sources, and every provider reads fiat
prices from it through `integration/exchange-rate`. `core/rayl-api` is its wire
contract and holds the production base URL.

The store app reports anonymous usage data to the same backend through
`core/telemetry`, unless the user turned it off. The
[catalogue](../core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt)
in the wire contract is the only definition of what an event may carry: the app
builds events from it and the backend drops everything else. Events hold listed
values and bounded numbers, never an identifier or free text, and no third-party
SDK is involved. The [privacy policy](../distribution/privacy.md) promises this
to users.

The dependency rules in [`verifyModuleDependencies`](../build.gradle.kts)
keep providers independent of one another and of the app, neutral modules
independent of the app and providers, and the Nostr SDK independent of Rayl.
Backend modules share only their wire contracts (`core/rayl-api`) with client
code; clients do not depend on backend implementation.

## Platform composition

Common Kotlin owns application state and payment logic. Android renders with
Compose; iOS renders with SwiftUI through Kotlin controllers and snapshots
exported by Rayl's `Shared` framework. Reusable native views live beside
their feature or provider, with Rayl connecting platform lifecycle to the
selected experience. Rayl registers no URL schemes and handles no incoming
links; payment input comes from the camera and the app's own UI.
