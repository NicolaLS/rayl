# Rayl Terms of Use

Last updated: November 3, 2026

These terms govern your use of **Rayl** for Android and iOS, maintained by
**Bitcoin Coast** ("we" or "the maintainer"). For questions, contact
[rayl@nicolasusca.com](mailto:rayl@nicolasusca.com). By using Rayl, you
agree to these terms. Data handling is described in the
[Privacy Policy](privacy.md).

## Self-custodial software

Rayl is open-source software for paying with Bitcoin over Lightning. It comes
with a built-in self-custodial wallet ("Rayl's wallet"), and you can instead
connect a wallet you already have, such as a Blink account, a Nostr Wallet
Connect (NWC) wallet, or a Spark wallet.

- Only you control your funds. The maintainer does not hold or custody funds,
  cannot access, freeze, reverse, or recover them, and has no Rayl account for
  you.
- The maintainer is not a bank, payment intermediary, exchange, or money
  transmitter, and does not provide financial, tax, or legal advice.
- Breez, the Spark network and its operators, your wallet provider, Nostr
  relays, password managers, Lightning-address and LNURL services, and network
  operators are independent services governed by their own terms. Rayl depends
  on them to work, and they may be unavailable, delay payments, or change.
- You are responsible for complying with the laws that apply to you.

## Rayl's wallet and your passkey

Rayl's wallet is created on the Spark network through the Breez SDK when you
set it up. Its key is derived from a passkey stored in your password manager.

- **Your passkey is the only backup of Rayl's wallet.** Rayl never shows or
  exports the key and has no recovery phrase. If you lose your passkey (for
  example, you delete it, or your password manager doesn't keep or sync it) and
  the copy on your device is gone as well (for example, after deleting the
  app, changing phones, or losing the device), your funds are lost for good.
  The maintainer cannot recover them.
- Keep the passkey in a password manager you trust and back up, and don't delete
  it while the wallet holds funds.
- Anyone who can use your passkey, including through other apps that use Breez
  passkey login, can open the wallet and spend its funds.
- Spark is new technology run by independent operators who take part in every
  transfer. It may have defects, and its operators or Breez may be unavailable.
- Keep only amounts you are prepared to lose and plan to spend.

## External wallets

- You are responsible for the wallet credentials you connect (a Blink API key,
  an NWC connection, or a Spark recovery phrase), the permissions and spending
  limits you grant, and keeping them safe.
- Your wallet provider governs your account and funds there. Removing a wallet
  from Rayl does not revoke its credentials at the provider.

## Payments, fees, and Auto Pay

- **Review payment details carefully:** Check the recipient and amount before
  you pay. Amounts in your display currency are estimates based on exchange
  rates, not guaranteed quotes.
- **Fees:** Rayl adds no fees of its own. Your wallet and the Lightning network
  may charge routing fees, which Rayl does not show before paying. After a
  payment, Rayl shows the fee your wallet reports, or "unknown" when the
  wallet doesn't report one.
- **Auto Pay:** Depending on your confirmation settings, scanning or opening a
  payment request below your threshold pays it without asking. To confirm
  every payment, set "Confirm Payment" to "Always" in Settings.
- **Payments are final:** Lightning payments are generally irreversible. A
  timeout or an unknown status does not prove that a payment failed; check
  your wallet before you retry, to avoid paying twice. Closing Rayl or changing
  wallets does not cancel a payment in progress.

## Open-source license and app stores

Rayl's source code is available at <https://github.com/NicolaLS/rayl> under its
open-source license. These terms do not restrict permissions granted by that
license.

Downloads from the Apple App Store are subject to Apple's
[Standard End User License Agreement](https://www.apple.com/legal/internet-services/itunes/dev/stdeula/)
and App Store terms. Downloads from Google Play are subject to Google Play's
terms. Neither Apple nor Google operates your wallet or provides payment
services through Rayl.

## Disclaimer of warranties and limitation of liability

To the maximum extent permitted by applicable law, Rayl is provided "AS IS" and
"AS AVAILABLE", without warranties of any kind, whether express, implied, or
statutory. You use Rayl at your own risk. The maintainer is not liable for any
loss or damages arising from your use of Rayl, including lost funds, a lost or
deleted passkey, software defects, unavailable or faulty third-party services
(such as Breez, Spark, or your wallet provider), exchange-rate estimates,
incorrect payment details, or unauthorized access to your device, passkey, or
credentials.

Nothing in these terms excludes or limits consumer rights, warranties, or
liability that cannot be excluded or limited under applicable law.

## Changes

We may update these terms and the date above. The current version is always in
Rayl's source repository, and continuing to use Rayl after a change means you
accept it.
