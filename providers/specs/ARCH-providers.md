# ARCH-providers: Wallet provider ownership

Within [ARCH-rayl](../../specs/ARCH-rayl.md), each provider owns its
wallet connection and payment execution. Shared `core/payment` utilities handle
payment input; `feature/payment-ui` supplies presentation models, user intents,
and platform UI. Payment features translate their state into that shared
presentation.

The [provider experience requirement](../../specs/REQ-provider-experience.md)
requires consistent UI and UX across all providers. Shared platform shells own
primary navigation; neutral credential forms, Scan/session-history presentation,
and wallet-removal actions own common interactions. Provider composition supplies credentials,
capability-specific controls, and execution callbacks.

## Composition and execution

Blink and NWC assemble their runtimes in their `experience` modules around the
provider-neutral [`feature/payment-flow`](../../feature/payment-flow), which owns
admission, LNURL resolution, confirmation, session history, and presentation.
Each provider's `feature/payment` supplies a `PaymentSettlement` that chooses
funding and submits payments through its own integration.
[Blink](../blink/integration/blink) wraps a GraphQL API and BTC or USD funding
wallets; [NWC](../nwc/integration/nwc) wraps the Nostr Wallet Connect client.
Their integrations own connection credentials.
Providers receive an explicit fresh-setup or restore mode. Fresh setup never
loads prior credentials. Platform storage cleanup is available independently of
runtime creation, so Rayl can enforce
[SPEC-wallet-lifecycle](../../specs/SPEC-wallet-lifecycle.md) before mounting an
experience.

The Rayl provider is Rayl's built-in wallet rather than a connection: hosts mount
it whenever no external wallet is configured, and its Settings offer the external
wallets the host supplies. It also runs on `feature/payment-flow`. Its
[`integration/pocket`](../rayl/integration/pocket) owns an app-owned Spark wallet
through the Breez SDK: it generates the pocket key on first use, keeps it in
device-only platform storage, quotes and pays invoices, and creates funding
invoices. It shares no code with the Spark provider, and its key outlives the
provider session as defined by
[SPEC-wallet-lifecycle](../../specs/SPEC-wallet-lifecycle.md).

Spark defines wallet and payment interfaces in
[`application`](../spark/application): `WalletAccess` controls wallet/session
access and `PaymentEngine` controls preparation and submission. Its payment
feature consumes `PaymentEngine`; [`integration/wallet`](../spark/integration/wallet)
provides platform credential/storage implementations and the Breez Spark adapter.
Spark's [`experience`](../spark/experience) assembles these parts and their platform
presentations; Rayl supplies product configuration and platform entry points.
The experience owns its wallet session lifetime. Disposal stops operations and
releases SDK resources while preserving credentials. The suspending `close()` API
reports cleanup failures and permits retry; non-suspending platform teardown is
best effort.
Successful wallet removal is reported separately from an initially empty wallet,
so hosts return to provider selection only after removal has completed.

## Native UI ownership

Provider-owned Swift code uses its own provider and provider-neutral shared
types and views. It does not depend on another provider or on Rayl's app code.
Shared Swift code remains provider-neutral. Rayl assembles the provider and
shared sources in its Xcode targets and connects them to the selected experience.
[`verifyModuleDependencies`](../../build.gradle.kts) enforces Kotlin module
dependency boundaries; Swift ownership is checked in code review because these
sources compile together in the app target.

## Shared services

Rayl, Blink, and NWC resolve LNURL through `integration/lnurl` and the shared
`LnurlInvoiceResolver`. Spark extends the shared input parser for Spark invoices
and delegates LNURL preparation to Breez.

The [shared Hub](../../feature/payment-hub/specs/ARCH-payment-hub.md) emits
payment requests to provider coordinators. Providers execute them and report
successful target payments back to the Hub.
