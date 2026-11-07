# Local NWC stack

Two Nostr Wallet Connect wallet services on regtest for Rayl's NWC wallet integration tests
(qa/test-plan.md §6.9), so a failure on only one of them points at that service rather than at
Rayl:

- Core Lightning (`payer`) with the [cln-nip47](https://github.com/daywalker90/cln-nip47) plugin;
- [Alby Hub](https://github.com/getAlby/hub) (`albyhub`), the most used NWC wallet service, with
  its default embedded node (LDK) on this regtest bitcoind.

Both have a channel to a third node, `receiver` (Core Lightning). Also a nostr relay and a small
helper API. Revived from the Blip E2E harness (`d17dc43b`).

```sh
tools/nwc-stack/bin/up      # start (needs Docker or OrbStack, jq, curl); about two minutes
./gradlew :providers:nwc:integration:nwc:walletIntegrationTest
tools/nwc-stack/bin/down    # stop; --clean also removes the volumes
```

Rayl connects to `payer` or `albyhub` and pays invoices of the `receiver` node; the receiver's
invoice says what really arrived. `ClnNip47WalletIntegrationTest` and
`AlbyHubWalletIntegrationTest` run the same tests (`NwcWalletServiceContract`) against each. The
Maestro flows use `payer` only. The helper (`http://127.0.0.1:8082`) creates NWC connections
(`GET /get-nwc-uri?node=payer|albyhub&label=…&budget_msat=…`) and invoices (`POST /create-invoice` with
`node`, `amount_msat` or `any`, `label`, optional `expiry`), and reports invoice status
(`POST /invoice-status`). The relay is `ws://127.0.0.1:8081`, or `ws://10.0.2.2:8081` from the
Android emulator.

The relay must acknowledge ephemeral events (kinds 23194/23195): nostr-rs-relay 0.8.9 doesn't,
and every NWC request then looks unanswered. 0.9.0 does.

`bin/up` sets Alby Hub up through its HTTP API, the same steps as its first-run screens: setup
with an unlock password (`rayl-regtest`), start, fund the on-chain wallet, open a 1M-sat channel
to `receiver`. Its UI is at `http://127.0.0.1:8083`. Usage events to Alby and Alby account
linking are off. The version is pinned in `docker-compose.yml`; upgrade it deliberately.

Alby Hub keeps one relay subscription per NWC connection, and nostr-rs-relay 0.9.0 allows 32 per
client connection (not configurable). The helper therefore deletes its oldest Alby Hub test
connections (named `rayl-test:…`) beyond 20. Without that, connections from a few test runs
pile up and new ones never answer ("Wallet discovery deadline exceeded").
