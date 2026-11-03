# Mainnet acceptance protocol (M11)

Draft for 1.1, 2026-11-02. Run once per release candidate with real money (test plan §7).
**Budget: 1,000 sats per release candidate (D-04).** Record results in the release's
`test-log.md`; anything unexpected is filed in Linear.

The protocol also verifies the stub assumptions the host tests rely on
([stub-assumptions.md](../stub-assumptions.md)); the IDs are noted per step.

## Setup

- Dedicated test wallets, funded once: Rayl's wallet (passkey on a test phone), a Blink
  account (API key with receive and send), an NWC wallet (e.g. Alby Hub), a Spark wallet
  (test recovery phrase). Each holds about 300 sats.
- Payments go between these wallets wherever possible, so only routing fees are spent.
  Expected spend per run: fees plus at most ~100 sats paid to outside services.

## Steps per wallet in use (Rayl, Blink, NWC, Spark)

| # | Step | Expected | Verifies |
|---|---|---|---|
| MN-1 | Pay a 21-sat invoice created by another test wallet | Paid; receipt fee matches the paying wallet's history | A3, A5 |
| MN-2 | Pay a Lightning address of another test wallet, typed with a capital first letter | Resolves and pays | MOB-89 |
| MN-3 | Pay an amountless invoice with a fiat amount (€0.02) | Wallet history shows the quoted sats, rounded up | PR-01 |
| MN-4 | Restart the app, scan MN-1's invoice again | Rayl, Spark, Blink: success marked "already paid"; NWC: record the wallet's error text | A1, A6, MOB-90 |
| MN-5 | Note the receipt's fee when the wallet reports none | Record whether Blink/NWC report a fee (MOB-94) | A5, A6 |
| MN-6 | Rayl's wallet only: receive 50 sats through "add funds" from another test wallet | Shown with the amount | A2 |

## Wallet lifecycle (once per run)

| # | Step | Expected | Verifies |
|---|---|---|---|
| MN-7 | Spark: remove the wallet, re-import the same phrase | Same balance (L13) | A4 |
| MN-8 | Use Blink, then go back to Rayl's wallet | Same balance, no passkey prompt | P5, P7 |

## Record

Per run: build, devices, sats spent (start and end balances per wallet), each step's result,
and the error texts collected in MN-4/MN-5.
