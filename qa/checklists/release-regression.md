# Release regression checklist (M10)

Draft for 1.1, 2026-11-02. Run once per release candidate on the App Live matrix (test plan
§7, D-05), on both platforms, with the release build. Record each run in the release's
`test-log.md` (build, device, OS, result per ID, evidence for failures). A failure is filed
in Linear before the run continues.

Matrix: oldest supported Android and iOS; two Android makers; one low-end Android; the oldest
iPhone that runs iOS 18.5 and a current iPhone. Passkey items (R) need a device whose password
manager supports PRF; check once whether App Live devices can complete a passkey ceremony
(PR-10), otherwise run R on the local Samsung and a real iPhone.

## B. Build

| ID | Check | Expected |
|---|---|---|
| B1 | Version | 1.1.0; Android versionCode ≥ 3 (1.0 used 2); iOS build number above 1.0's |
| B2 | Release branch gating | Only the providers planned for 1.1 are reachable (release branches remove unready ones) |
| B3 | Mainnet | Release build talks to mainnet; the Breez API key is present (the app reaches the wallet screen) |

## U. Install and upgrade

| ID | Check | Expected |
|---|---|---|
| U1 | Fresh install | Welcome, terms, passkey setup; no saved connection; Rayl's wallet in use afterwards |
| U2 | Upgrade 1.0 → 1.1 with Blink connected | Blink still connected without re-entering the key; Hub contacts, widgets, Auto Pay threshold and display currency kept; no Rayl onboarding shown |
| U3 | Upgrade 1.0 → 1.1 with NWC connected | As U2 for NWC |
| U4 | iOS: delete and reinstall after connecting Blink | Starts fresh, no saved connection (Keychain items cleared, L10) |

## R. Rayl's wallet and passkeys

| ID | Check | Expected |
|---|---|---|
| R1 | New passkey | Wallet set up; relaunch opens without a passkey prompt |
| R2 | Existing passkey with accounts | Accounts offered, including Rayl's own; choosing one opens it |
| R3 | Password manager without PRF | Asked to use another password manager; nothing set up |
| R4 | No passkey support | Onboarding offers the external wallets |
| R5 | Use another wallet, then go back to Rayl | Same wallet, same balance, no passkey prompt (P5, P7) |
| R6 | Receive: add funds | Invoice shown or offered to another wallet app; a payment to it is shown with the amount |

## P. Paying (each wallet in use: Rayl, Blink, NWC, Spark)

| ID | Check | Expected |
|---|---|---|
| P1 | Scan an invoice below the threshold | Paid without confirmation; success with amount and fee |
| P2 | Invoice of exactly the threshold | Confirmation shown; Settings says "10,000 sats or more" |
| P3 | "Always" mode | Every payment asks |
| P4 | Amountless invoice | Amount entry; the entered amount is paid exactly |
| P5 | Lightning address, also typed as `Alice@…` | Resolves and pays (MOB-89) |
| P6 | Fiat entry in German or Spanish | Decimal key shows ","; €1,50 is quoted and paid as €1.50 |
| P7 | Rescan a paid invoice in the same session | Receipt opens; nothing paid |
| P8 | Rescan a paid invoice after restarting the app | Success marked "already paid" on Rayl, Spark, Blink; NWC shows the wallet's message (O-07, MOB-90) |
| P9 | Expired invoice | Rejected before the wallet is asked |
| P10 | No network | Clear error; nothing recorded as failed if it may have been sent |
| P11 | Hub preset widget | Always asks for confirmation; pays the stored amount |
| P12 | Pay a new Lightning address | Offer to save it as a contact |

## E. External wallets

| ID | Check | Expected |
|---|---|---|
| E1 | Connect Blink, NWC, Spark (phrase word by word, paste) | Connected; relaunch restores it |
| E2 | Cancel each setup | Back to Rayl's wallet; nothing saved |
| E3 | Remove, then connect the same wallet again | Credentials must be entered again |
| E4 | Rotate the screen during setup (Android) | Setup keeps its state (L11) |
| E5 | Spark: remove, re-import the same phrase | Same balance (L13) |

## I. Interruptions (PR-17)

| ID | Check | Expected |
|---|---|---|
| I1 | Background the app while a payment is being sent, return | Outcome shown; never shown as failed if it may have paid |
| I2 | Kill the app while paying, reopen | No duplicate payment; wallet history shows one payment |
| I3 | Spark: trigger "wallet removed / reset required" during a payment, if possible (O-02) | The payment's outcome is not lost |
| I4 | Airplane mode during confirmation | No payment; clear error |

## L. Languages, legal, look

| ID | Check | Expected |
|---|---|---|
| L1 | German and Spanish | No English leftovers or truncation on main screens; amounts formatted per language |
| L2 | Terms and privacy links | Open the documents updated for 1.1 (MOB-96, MOB-97) |
| L3 | Store listing screenshots and text | Match 1.1 (MOB-98) |
| L4 | Google Play Data safety and App Store privacy labels | Match the 1.1 privacy policy: financial info and payment history on the device, the passkey login, the services that receive data (MOB-96) |
