# Stub assumptions

Host tests replace external systems with stubs (decided 2026-11-02: stub first, build real
test infrastructure once we know what it needs). A stub encodes an assumption about the real
system; if the assumption is wrong, the tests stay green while the app fails. This register
lists each assumption, where it is encoded, and how it gets verified for real. Together these
rows are the requirements for later integration infrastructure (regtest, recorded responses).

State on 2026-11-03: A1–A4 are checked against the real Breez SDK on regtest
(`./gradlew :tools:regtest:regtestContractTest`); two of them were wrong and fixed (MOB-101).
A9 runs on iOS too (simulator). A10 is checked on a real Android Keystore; iOS needs an
app-hosted test target.

| ID | Real system | Assumption | Encoded in | Verify by |
|---|---|---|---|---|
| A1 | Breez SDK (Rayl wallet, Spark) | A second send to a paid invoice fails with a message containing `status: AlreadyExists`, during quote or send | `PaymentProblemTest` (pocket, Spark), settlement tests (MOB-90) | **Verified on regtest** (`BreezSdkContractTest`); mainnet MN-4 |
| A2 | Breez SDK | Refusals: insufficient funds and expired invoices are quoted fine and refused when sending, as `SparkException` texts containing "insufficient funds" / "has expired"; "Failed to select leaves" (balance settling); `NetworkException` for connectivity | `PaymentProblemTest` | **Insufficient funds and expired verified on regtest; the original assumption (`SdkException.InsufficientFunds`, "Invoice has expired") was wrong: MOB-101, fixed.** Balance settling and network: not yet provoked |
| A3 | Breez SDK | `prepareSendPayment` quotes the fee before sending; the paid fee equals what the SDK reports | Rayl/Spark settlement tests | **Verified on regtest for the Lightning route**; mainnet MN-1 |
| A4 | Breez SDK / Spark | Erasing local data and re-importing the same phrase finds the same wallet and balance | `WalletRemovalTest`, lifecycle tests | **Verified on regtest**; mainnet MN-7 |
| A5 | Blink GraphQL API | Response shapes and `PaymentSendResult` values (SUCCESS, PENDING, ALREADY_PAID, FAILURE; anything else is unknown) as in the canned JSON; the transaction's fee is reported for paid payments | `BlinkApiClientTest`, `BlinkWalletPaymentTest`, settlement tests | Mainnet MN-1, M4, M5; schema check against Blink's published schema |
| A6 | NWC wallets (NIP-47) | `pay_invoice` returns `preimage` and optionally `fees_paid`; errors carry a code and free text; no already-paid code | `NwcWalletConnectionTest`, settlement tests | Mainnet MN-4, M5 with at least one NWC wallet (Alby Hub) |
| A7 | LNURL servers | LUD-06/16 responses as in the fixtures; most bind invoices with `description_hash`; lowercase user names | `KtorLnurlPayClientTest`, `LnurlInvoiceResolverTest`, corpus | Mainnet MN-2 against real services (e.g. Wallet of Satoshi, Blink, Alby addresses) |
| A8 | Rayl price backend | Response `{"asOf":…,"prices":{CODE:price}}`; 503 when no source is fresh | `RaylBitcoinPriceProviderTest`, `RaylServerTest` (pinned shape) | Backend tests run against the real module; a production smoke request per release |
| A9 | Platform locale data | Formatting per language as on the JVM host | `AmountFormatterTest` | **iOS formatter verified** (simulator run); Android ICU on device via checklist L1, P6 |
| A10 | Android Keystore / iOS Keychain | Store, read, "unreadable" and delete behave as the fakes (`FakeKeyStore`, `TestVault`) | Pocket and Spark tests | **Android verified** (device tests on an emulator: pocket key store, Spark vault, secure settings). iOS Keychain: needs an app-hosted XCTest target (carried over); checklist U4, R1, R5 |
| A11 | Passkey providers (PRF) | Breez passkey login derives the same key for the same passkey and account; labels are listed and stored through Breez's label store | `PocketSetupTest` | Checklist R1–R3, R5 |
