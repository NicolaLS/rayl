# ARCH-payment-hub: Shared payment Hub

The Hub is a provider-neutral feature in
[ARCH-rayl](../../../specs/ARCH-rayl.md). Common Kotlin owns its saved
state and behavior, with Compose and SwiftUI presentations.

## Local state and payment handoff

Contacts, payment actions, and placed widgets have separate identities.
`DefaultPaymentHubRepository` persists them in app-scoped settings;
`WidgetHubViewModel` derives the gallery and displayed tiles from that state.

[`PaymentHubController`](../src/commonMain/kotlin/xyz/lilsus/raylsuite/feature/paymenthub/host/PaymentHubController.kt)
emits payment intents to the
[provider coordinators](../../../providers/specs/ARCH-providers.md).
Providers own execution and report successful target payments back to update
Hub usage history. The Hub does not submit wallet payments itself.

## Information widgets

Some widgets show live information instead of people to pay. They reach the
wallet experience only through `PaymentHubController`: it lends them the
experience's bitcoin prices, and takes an invoice a widget obtained from its
service into the same payment flow as a scanned invoice.

The PPQ.AI and Claro widgets are the Hub's third-party services. Their clients
belong to the Hub, not to a provider, and the phone calls each service directly:
no Rayl backend sits in between. A service only issues an invoice; the wallet
pays it.

The PPQ.AI API key lives in the Hub's own credential store, outside the Hub
document and outside the external-wallet storage that
[SPEC-wallet-lifecycle](../../../specs/SPEC-wallet-lifecycle.md) erases, and is
deleted with the last PPQ.AI widget. A Claro widget keeps only the phone number
it tops up, in the Hub document.

A Claro purchase ends for the user with the wallet's payment. The Hub keeps the
order in its document and, while it is on screen, asks Claro's payment provider
whether it was paid and Claro whether it was delivered. What it learns goes only
to the anonymous usage data described in [ARCH-rayl](../../../specs/ARCH-rayl.md),
as counts; then the order is forgotten.
