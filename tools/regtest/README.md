# Regtest bank

A developer-only Spark wallet on Lightspark's hosted Regtest network: the
counterparty for funding a Rayl pocket and for invoices Rayl pays. Debug builds
of Rayl use the same network, so no Docker, local nodes, or Breez API key are
needed. It is a separate JVM executable and never part of the apps.

On first use it creates its wallet under `tools/regtest/.local/` (gitignored;
override with `RAYL_REGTEST_HOME`). The launcher builds the tool once and only
rebuilds after its sources change.

```sh
tools/regtest/regtest status                 # balance and funding addresses
tools/regtest/regtest pay <invoice>          # pay a Rayl invoice
tools/regtest/regtest pay --from android     # pay the latest invoice on the device
tools/regtest/regtest invoice 500 --open ios # Rayl pays this (scan the QR); waits until paid
```

## Manual QA

1. **Fund the bank once.** Run `status` and paste its Spark address into the
   [Lightspark Regtest faucet](https://app.lightspark.com/regtest-faucet); the
   funds arrive without mining. The deposit address works too: follow the
   transaction on the [Regtest explorer](https://regtest-mempool.us-west-2.sparkinfra.net/),
   then run `status` again to claim it. Spark transfers are off-chain and do not
   appear in the explorer.
2. **Add funds to Rayl.** In a debug build, tap Add funds on Settings → Balance.
   The invoice has no amount, so pass one: on Android,
   `tools/regtest/regtest pay --from android --amount 1000` pays the latest
   invoice: debug builds log it under the `RaylRegtest` logcat tag, so no
   copying is needed (set `ANDROID_SERIAL` when several devices are connected).
   When no other wallet app opens it, Rayl shows the invoice; on the iOS
   simulator, tap Copy invoice and run
   `tools/regtest/regtest pay "$(pbpaste)" --amount 1000`; its clipboard syncs
   with the Mac.
3. **Pay from Rayl.** `tools/regtest/regtest invoice 500 --open android` (or
   `ios`) opens the invoice in the running app as a `lightning:` link. Confirm
   the payment in Rayl; the command reports when it arrives.

## Limits

Regtest has no Lightning network. Invoices between Spark wallets settle as
Spark transfers, so routing, fees, LNURL, and Lightning addresses need mainnet
checks with small amounts.
