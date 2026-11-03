# Rayl Privacy Policy

Last updated: November 3, 2026

This policy covers **Rayl** for Android and iOS, maintained by **Bitcoin Coast**
("we" or "the maintainer"). For privacy questions or requests, contact
[rayl@nicolasusca.com](mailto:rayl@nicolasusca.com) or open an issue at
<https://github.com/NicolaLS/rayl/issues>. Please do not send API keys,
connection secrets, recovery phrases, or payment preimages.

Rayl is an open-source app for paying with Bitcoin over Lightning. It comes with
a built-in self-custodial wallet ("Rayl's wallet"), and you can instead connect
a wallet you already have: a Blink account, a wallet through Nostr Wallet
Connect (NWC), or a Spark wallet.

There is no Rayl account. Rayl contains no analytics, advertising, tracking, or
crash-reporting SDKs, and it sends no logs to us. The maintainer does not
operate a wallet, payment processor, Nostr relay, or analytics service, and
never receives your wallet keys, passkey, balance, or payment history.

## Information stored on your device

Rayl stores the following information on your device to provide the features
you use:

- **Rayl's wallet:** The key of Rayl's wallet is derived from your passkey when
  you set the wallet up. Rayl keeps a copy of the key on the device, encrypted
  with the Android Keystore or in the iOS Keychain, so payments don't ask for
  the passkey. The wallet software (the Breez SDK) keeps the wallet's data on
  the device, including your balance, payment history, and the invoices you
  create to receive funds.
- **Your passkey:** The passkey itself is stored by your password manager (for
  example Google Password Manager or iCloud Keychain), which may sync it across
  your devices under its own terms. Rayl receives only the key derived from it.
- **External wallet credentials:** Your Blink API key, NWC connection URI, or
  Spark recovery phrase (and passphrase, if you set one) is stored in encrypted
  app storage on Android or the Keychain on iOS. For a Spark wallet, the Breez
  SDK also keeps the wallet's data on the device. Only one external wallet is
  connected at a time. Going back to Rayl's wallet, or cancelling a wallet
  setup, deletes that wallet's stored credentials and data. While you use an
  external wallet, Rayl's wallet key copy and data stay on the device so you
  can go back to it.
- **Contacts and Payment Hub widgets:** Contact names, Lightning addresses,
  widget layouts, preset amounts, payment comments, and the local payment
  counts and times used for Favorites and Recents are stored in local app
  storage. When you connect a Blink account, Rayl imports your Blink contact
  aliases and Lightning addresses into the Hub. Rayl does not access your
  device's address book.
- **Recent payments in Rayl:** Up to ten payment results shown in Rayl are kept
  in memory and are cleared when the app process ends or you change wallets.
  The wallet's own payment history is stored as described above.
- **Preferences:** Theme, language, display currency, and payment-confirmation
  settings are stored locally.
- **Camera and clipboard:** Camera frames are used only to scan QR codes on the
  device and are never saved or uploaded. Clipboard text is read only when you
  paste.

Android cloud backup and device-to-device transfer are turned off for Rayl's
data. On iOS, Rayl's Keychain items are device-only: they are never synced or
restored to another device. The key copy of Rayl's wallet stays in the
Keychain if you delete the app, so reinstalling Rayl on the same device opens
the same wallet. External wallet credentials left in the Keychain by an
earlier installation are deleted before a new wallet is connected.

## Network requests and third parties

Rayl contacts only the services needed for the features you use. Each service
sees standard network information such as your IP address, and processes it
under its own terms and privacy policy.

- **Breez and Spark (Rayl's wallet and Spark wallets):** The Breez SDK connects
  to Breez's services and to the services that run Spark, such as the Spark
  operators, Lightning service providers, and Bitcoin blockchain data services,
  to show your balance, send and receive payments, and keep the wallet in
  sync. These services see payment details such as amounts, invoices, and
  addresses. Breez may keep an encrypted copy of wallet data so the wallet
  stays the same across devices and apps that open it. Payments through Spark
  should not be considered private.
- **Passkey setup and login:** Creating or using the passkey of Rayl's wallet
  goes through your device's operating system and password manager. Release
  builds use Breez's shared passkey domain (keys.breez.technology). The names
  of the wallet accounts on your passkey (such as "Rayl") are stored on Nostr
  relays through Breez, so that Rayl, and other apps that use Breez passkey
  login with the same passkey, can offer them again. Rayl adds no personal
  details to them.
- **Blink:** When you use Blink, Rayl sends your API key and requests to
  retrieve wallet details, import Blink contacts, and submit or check payments.
  Blink processes your account under its
  [Privacy Policy](https://www.blink.sv/en/privacy-policy) and
  [Terms and Conditions](https://www.blink.sv/en/terms-conditions).
- **Nostr Wallet Connect (NWC):** When you use NWC, Rayl sends encrypted
  requests to the relays named in your connection URI. Those relays and your
  wallet service process them under their own terms.
- **Exchange rates:** Rayl requests aggregated Bitcoin exchange rates from the
  Rayl exchange-rate service to show amounts in your display currency. These
  requests contain no wallet credentials, contacts, payment history, or user
  identifiers.
- **Lightning addresses and LNURL:** Paying a Lightning address or LNURL
  contacts the domain it names to request an invoice, including the amount and
  any comment you enter. Wallet credentials are never sent to these services.
- **Google ML Kit (Android only):** The Android app uses Google ML Kit for
  on-device barcode scanning, which sends technical SDK diagnostics (such as
  device and app metadata, an installation identifier, timings, and error
  codes) to Google. Camera images and scanned codes stay on your device. See
  [ML Kit's data disclosure](https://developers.google.com/ml-kit/android-data-disclosure)
  and [Google's Privacy Policy](https://policies.google.com/privacy). The iOS
  app uses Apple's scanner instead.
- **Invoices you share:** An invoice you create to receive funds contains the
  amount and payment details. Anyone you share it with can read them.
- **External links:** Links such as wallet dashboards, support pages, or these
  documents open in your browser under the destination's privacy policy.

We do not sell personal data, use advertising, or track you across apps or
websites. Bitcoin and Lightning payments may be visible to the services that
route them, and on-chain transactions are public and permanent.

## Deletion and your choices

- **External wallets:** Go back to Rayl's wallet in Settings to delete the
  external wallet's credentials and data from the device. This does not delete
  your account at the provider or revoke a Blink API key or NWC connection; use
  the provider's controls for that.
- **Rayl's wallet:** Rayl does not offer to delete Rayl's wallet from within the
  app. On Android, clearing Rayl's storage or
  uninstalling Rayl deletes the key copy and wallet data from the device. On
  iOS, deleting the app deletes the wallet data, while the key copy stays in
  the device's Keychain until the device is erased. In both cases the funds
  stay in the wallet, and your passkey opens it again. If you delete the
  passkey as well, nobody can open the wallet or recover its funds.
- **Contacts and widgets:** Delete them in the Hub.
- **Everything else:** Clearing Rayl's storage on Android or deleting the app
  on iOS erases Rayl's local data.

Because we hold no data about you, we usually have nothing to access, correct,
or delete. You can still contact us with any request or question. For data held
by Breez, Blink, your password manager, or another service, contact that
service. Data recorded on Bitcoin, Spark, or Nostr networks cannot be deleted
by us.

## Children

Rayl is not directed at children, and we do not knowingly collect information
from them.

## Changes

We will update this policy and the date above when Rayl's data practices
change.
