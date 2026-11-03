# Nostr SDK

A Kotlin Multiplatform Nostr SDK. Rayl's NWC provider uses it through
`nwc-kmp`. It started as the standalone
[nostr-kmp](https://github.com/NicolaLS/nostr-kmp) repository and was imported
here with its history (MOB-100). It's no longer published to Maven Central.

The protocol model, codec, crypto, transport, runtime, and NIP support live in
small modules. Packages keep their original `io.github.nicolals.*` names.

## Modules

| Gradle project | Purpose |
| --- | --- |
| `:nostr:nostr-core` | Core event, key, filter, codec, transport, runtime, and NIP extension contracts. |
| `:nostr:nostr-codec-kotlinx` | JSON and Nostr wire codec backed by `kotlinx.serialization`. |
| `:nostr:nostr-crypto` | secp256k1 signing, hashing, ECDH, HKDF, AES-CBC, ChaCha20, HMAC, base64, and random bytes. |
| `:nostr:nostr-transport-ktor` | Ktor WebSocket transport (CIO on Android, Darwin on iOS). |
| `:nostr:nostr-runtime-coroutines` | Coroutine relay session and relay pool with reconnect, retry, deduplication, and observable flows. |
| `:nostr:nips:nip01` | NIP-01 messages, metadata events, and protocol reducer. |
| `:nostr:nips:nip04` | NIP-04 encrypted direct messages. |
| `:nostr:nips:nip42` | NIP-42 relay authentication. |
| `:nostr:nips:nip44` | NIP-44 v2 encrypted payload helpers. |
| `:nostr:nips:nip47` | NIP-47 Nostr Wallet Connect events, payloads, and encryption selection. |
| `:nostr:nwc-kmp` | High-level Nostr Wallet Connect client with typed wallet operations and notifications. |

`nwc-kmp` depends on all the other modules. A module that nothing in Rayl
needs doesn't belong here. NIP-45 and NIP-50 were dropped for that reason;
they remain in the original repository.

## Boundaries

- The SDK depends on no other Rayl module. `verifyModuleDependencies` enforces
  this, so the directory can be split out again with
  `git subtree split --prefix=nostr`.
- Modules use Rayl's `xyz.lilsus.raylsuite.kmp.library` convention: Android
  and iOS (`iosArm64`, `iosSimulatorArm64`) targets, and Rayl's ktlint style.
- Host tests run on the desktop JVM. [`build.gradle.kts`](build.gradle.kts)
  swaps in the JVM builds of libsodium and secp256k1 for them, because the
  Android builds ship only Android binaries.

## Build and test

From the repository root:

```sh
./gradlew :nostr:nwc-kmp:testAndroidHostTest
./gradlew :nostr:nostr-crypto:iosSimulatorArm64Test
```

Rayl's CI runs every module's `testAndroidHostTest` and `iosSimulatorArm64Test`.

## Documentation

- [Architecture](docs/architecture.md): event model, codecs, NIP registries, transport, runtime, and policies.
- [Security](docs/security.md): authentication boundaries, encryption, credentials, and key custody.
- [Recipes](docs/recipes.md): focused examples for typed messages, metadata, encryption, and NWC.
- [Nostr Wallet Connect client](nwc-kmp/README.md): wallet connections, payments, invoices, and notifications.
