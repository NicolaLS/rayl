# `distribution/` review for 1.1 (M9)

Reviewed on 2026-11-02 against what `main` does. Risk PR-21; release exit criterion 6.
All four documents still describe Rayl 1.0: a client for an external wallet (Blink, NWC).
1.1 makes **Rayl's own self-custodial wallet the default** (Breez SDK on Spark, key derived
from a passkey, a copy kept on the device), adds **Spark** as an external wallet, and lets
users **receive** funds into Rayl's wallet.

Legal wording is Nicola's call; the findings below say what is wrong or missing, not the final
text.

## `privacy.md` (MOB-96)

| # | Statement today | What 1.1 does |
|---|---|---|
| P1 | "Rayl is an open-source client that connects to a wallet you choose" | Rayl's built-in wallet is the default; no external wallet is needed |
| P2 | Stored on the device: Blink API key or NWC URI | Also: the key derived from your passkey (Keystore/Keychain), Spark recovery phrase and passphrase (encrypted), and the Breez SDK's local wallet data (balance, payment history) for Rayl's wallet and Spark |
| P3 | "Payment information … processed in memory … ten receipts … current session only" | True for the in-app receipts, but the Breez SDK keeps payment history persistently on the device for Rayl's wallet and Spark |
| P4 | "switching providers or removing your wallet deletes the stored credential" | Using another wallet keeps Rayl's wallet key copy and data on purpose (SPEC-wallet-lifecycle); only external credentials are deleted |
| P5 | Network requests: Blink, NWC relays, rates, LNURL, ML Kit | Missing: Breez and the Spark operators (Rayl's wallet and Spark), the passkey ceremony with the platform password manager and the relying-party domain, and Breez's passkey label store (account labels such as "Rayl") |
| P6 | Deletion: "Remove your wallet in Settings" | There is no user action that deletes Rayl's wallet data on the device except "set up again" when the key can't be read; deleting the app removes the local copy, and the passkey restores the same wallet. Funds stay on Spark. |
| P7 | Receiving | Funding invoices are created and shared from the device (new in 1.1) |

## `terms.md` (MOB-97)

| # | Statement today | What 1.1 does |
|---|---|---|
| T1 | "an open-source interface for connecting to an external wallet" | Includes a built-in wallet |
| T2 | "does not … open wallet accounts" | Rayl creates a Spark wallet through Breez during onboarding (still self-custodial) |
| T3 | (missing) | **The passkey is the only backup of Rayl's wallet; the key is never shown or exported.** Losing the passkey (deleted from the password manager, not synced) together with the device means losing the funds. This is the most important user-facing disclosure missing. |
| T4 | (missing) | Spark's trust model (operators co-sign transfers; unilateral exit) and that Breez and Spark are third-party services |
| T5 | "Verify … fees before sending a payment" | Fees are not shown before paying by default (decided 2026-11-02, MOB-93); the receipt shows the fee paid, and may show 0 when the wallet doesn't report it (MOB-94) |
| T6 | Auto Pay wording | Consistent with MOB-88 (the threshold amount itself asks) |

## Store listings (MOB-98)

| # | Finding |
|---|---|
| L1 | `en-US`: "Rayl is a payment companion, not a separate wallet. Connect a supported wallet…" and "Currently supports Blink." contradict 1.1 (built-in wallet; Blink, NWC and Spark) |
| L2 | `de-DE` and `es-ES` contain only "INSERT FINAL AFTER 1.0 RELEASE" |
| L3 | Outside the repo, to check: Google Play Data safety and App Store privacy labels need the same changes as P2–P5 (financial info, passkey, device storage) |

## `encryption.md` (MOB-99)

| # | Finding |
|---|---|
| E1 | The document itself says: "The historical assessment below does not cover Spark or its native SDK. Rayl requires reassessment before release." 1.1 ships the Breez SDK (Spark) in Rayl's wallet and as an external wallet, so the iOS export-compliance answers (`ITSAppUsesNonExemptEncryption`) and the France decision must be reassessed before the 1.1 submission. |
