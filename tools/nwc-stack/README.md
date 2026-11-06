# Local NWC stack

A Nostr Wallet Connect wallet service on regtest for Rayl's NWC wallet integration tests
(qa/test-plan.md §6.9): Bitcoin Core, two Core Lightning nodes (`payer`, `receiver`) with a
channel between them, the [cln-nip47](https://github.com/daywalker90/cln-nip47) plugin on both,
a nostr relay, and a small helper API. Revived from the Blip E2E harness (`d17dc43b`).

```sh
tools/nwc-stack/bin/up      # start (needs Docker or OrbStack, jq, curl); about a minute
./gradlew :providers:nwc:integration:nwc:walletIntegrationTest
tools/nwc-stack/bin/down    # stop; --clean also removes the volumes
```

Rayl connects to the `payer` node and pays invoices of the `receiver` node; the receiver's
invoice says what really arrived. The helper (`http://127.0.0.1:8082`) creates NWC connections
(`GET /get-nwc-uri?node=payer&label=…&budget_msat=…`) and invoices (`POST /create-invoice` with
`node`, `amount_msat` or `any`, `label`, optional `expiry`), and reports invoice status
(`POST /invoice-status`). The relay is `ws://127.0.0.1:8081`, or `ws://10.0.2.2:8081` from the
Android emulator.

The relay must acknowledge ephemeral events (kinds 23194/23195): nostr-rs-relay 0.8.9 doesn't,
and every NWC request then looks unanswered. 0.9.0 does.
