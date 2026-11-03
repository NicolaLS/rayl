# nwc-kmp

`nwc-kmp` is a Kotlin Multiplatform client for Nostr Wallet Connect (NWC,
NIP-47). It provides a typed API for connecting to an NWC wallet service,
discovering wallet capabilities, paying invoices, creating invoices, listing
transactions, and receiving wallet notifications.

This client is the `:nostr:nwc-kmp` module of the [Nostr SDK](../README.md),
with Android and iOS targets. It depends directly on the sibling SDK modules.

## Features

- Parse `nostr+walletconnect` URIs and `nostrnwc` callback links.
- Connect to NWC wallet services over Nostr relays with Ktor WebSockets.
- Use NIP-44 v2 when the wallet advertises support, with NIP-04 fallback.
- Fetch wallet info events and typed `get_info` details.
- Call common NWC methods: `get_balance`, `pay_invoice`, `make_invoice`,
  `lookup_invoice`, `list_transactions`, `pay_keysend`, and multi-pay methods.
- Collect `payment_received` and `payment_sent` notifications when supported.
- Handle failures through `NwcResult` and typed `NwcError` values.

## Installation

Depend on the project from a Rayl module:

```kotlin
commonMain.dependencies {
    implementation(project(":nostr:nwc-kmp"))
}
```

## Quick Start

```kotlin
import io.github.nicolals.nwc.NwcClient
import io.github.nicolals.nwc.NwcResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

suspend fun printBalance(connectionUri: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = NwcClient.fromUri(connectionUri, scope)
        ?: error("Invalid NWC connection URI")

    try {
        if (!client.awaitReady(timeoutMs = 10_000)) {
            error("NWC client did not become ready")
        }

        when (val result = client.getBalance()) {
            is NwcResult.Success -> {
                println("Balance: ${result.value}")
            }
            is NwcResult.Failure -> {
                println("Balance failed: ${result.error.message}")
            }
        }
    } finally {
        client.close()
        scope.cancel()
    }
}
```

`NwcClient.fromUri` and `NwcClient(uri, scope)` start connecting immediately.
You can still call `connect()` explicitly; it is idempotent unless
`forceReconnect = true`.

## Documentation

- [Client Lifecycle](docs/client.md): state, connection behavior, wallet info,
  notifications, and timeouts.
- [Recipes](docs/recipes.md): examples for each supported wallet operation.
- [Deep Links](docs/deep-links.md): app-to-wallet connection requests and
  callback parsing.
- [API Reference](docs/reference.md): public models, errors, operations, and
  compatibility notes.

## Development

From the Rayl repository root:

```sh
./gradlew :nostr:nwc-kmp:testAndroidHostTest
./gradlew :nostr:nwc-kmp:iosSimulatorArm64Test
```

The source lives in `nwc-kmp/src/commonMain/kotlin/io/github/nicolals/nwc`.
Common tests live in `nwc-kmp/src/commonTest/kotlin`.

## Security Notes

An NWC connection URI contains the client secret used to sign requests and
decrypt wallet responses. Treat the full URI like a credential:

- Do not log it.
- Do not send it to analytics.
- Store it only in secure app storage.
- Prefer showing wallet capabilities to the user before enabling payments.

The default constructor installs WebSockets and owns its transport resources.
`close()` cancels initialization as well as active subscriptions and permanently
closes the client. Injected transport factories and HTTP clients stay caller-owned.
Incoming wallet events are authenticated locally before decryption and correlation.

For alternative transports, asynchronous signing/encryption, or additional wallet
methods, see [Injecting Capabilities](docs/client.md#injecting-capabilities).

A `NwcConnection` descriptor contains public wallet/client keys and relay URLs.
Use `NwcClient(connection, scope, signer, cipher)` for remote or hardware accounts
without exporting a private key or constructing a credential-bearing URI.
