# Smoke checklist (every release candidate)

At most 20 minutes per platform. Run on **the exact build that goes to the store** (TestFlight
build, Play internal-testing build), on your own Android phone and iPhone. It blocks the
release: set both stores to manual release (App Store "Manually release this version", Play
"Managed publishing" or a staged rollout) and release only after it passes.

Record each run in `qa/releases/<version>/runs/<date>-smoke.md`: version and build number,
device and OS, result per ID, sats spent. Never record keys, phrases, NWC URIs or invoices.
A failure is filed in Linear (`MOB`, label `Bug`) before continuing.

The full [release regression checklist](release-regression.md) (App Live) and the full
[mainnet protocol](mainnet-acceptance.md) run once per minor release.

## Before you start

- About 300 sats in Rayl's wallet on the test phone, and test accounts for the external wallets
  you check this time (Blink, NWC, Spark), with about 100 sats each.
- A second wallet app to create invoices.

## Steps

| ID | Step | Expected |
|---|---|---|
| S1 | Check the version in Settings | The version and build number you're about to release |
| S2 | Open the app from a cold start | Scan screen with the camera ready, no error, no noticeable delay compared with the last release |
| S3 | **Rayl's wallet:** pay a 21-sat invoice from your second wallet (below the Auto Pay threshold) | Paid without asking; receipt shows the amount and a fee; your second wallet received it (MN-1) |
| S4 | Scan the same invoice again | Receipt or "already paid"; nothing paid twice |
| S5 | Pay a Lightning address, typed with a capital first letter (`Alice@…`), with a fiat amount | Resolves; the sats quoted match the fiat amount; paid (MN-2) |
| S6 | Pay an invoice at or above the threshold | Confirmation shown before paying |
| S7 | Kill the app, reopen it, scan S3's invoice again | Success marked "already paid"; nothing paid (MN-4) |
| S8 | **Cards** (rotate between Blink, NWC and Spark per release): add one in Settings → Wallets → Add a card, pay 21 sats, rescan the invoice; add a second card of the same provider with other credentials, switch between the two and Rayl (Settings → the wallet at the top → tap a wallet), pay from each | Each card pays from its own wallet; rescan doesn't pay again; the list names them "Blink", "Blink 2"; Settings → Details shows the active card's own settings (Blink funding wallet, NWC details) |
| S9 | Settings → Details → Remove card | Rayl pays with the same balance, no passkey prompt; the card's credentials are gone (adding it again asks for them); other cards are unchanged |
| S10 | Switch the language to German or Spanish; open the payment screen and Settings | Translated, amounts formatted for the language |
| S11 | Open Terms and Privacy Policy from Settings | Both open the current documents |

## When something fails

- A payment problem (S3–S9): stop, file it as S1/S2, don't release.
- A display or language problem (S10, S11): file it; Nicola decides whether it blocks.
