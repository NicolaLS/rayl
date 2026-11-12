# Rayl Privacy Policy

Last updated: November 12, 2026

This policy covers **Rayl** for Android and iOS, maintained by **Bitcoin Coast**
("we" or "the maintainer"). For privacy questions or requests, contact
[rayl@nicolasusca.com](mailto:rayl@nicolasusca.com) or open an issue at
<https://github.com/NicolaLS/rayl/issues>. Please do not send API keys,
connection secrets, recovery phrases, or payment preimages.

Rayl is an open-source app for paying with Bitcoin over Lightning. It comes with
a built-in self-custodial wallet ("Rayl's wallet"), and you can instead connect
a wallet you already have: a Blink account, a wallet through Nostr Wallet
Connect (NWC), or a Spark wallet.

There is no Rayl account. Rayl contains no advertising, tracking, third-party
analytics, or crash-reporting SDKs, and it sends no logs to us. The one
exception is the code scanner on Android, Google ML Kit, which sends Google
technical diagnostics as described under
[Network requests and third parties](#network-requests-and-third-parties). The only data
Rayl sends to us is the anonymous usage data described below, which you can
turn off. The maintainer does not operate a wallet, payment processor, or
Nostr relay, and never receives your wallet keys, passkey, balance, or payment
history.

## Anonymous usage data

To find problems and keep Rayl fast, the app sends anonymous usage data to the
Rayl backend, which we operate. You can turn it off at any time in
**Settings → Share anonymous usage data**; turning it off also discards
anything not yet sent.

- **What is sent:** counts and timings. For example: that a payment was made,
  with which kind of wallet, whether it succeeded or what category of error it
  hit, and how long each step took; how long the camera needed to start and
  read a code; which onboarding steps were completed; that a setting was
  changed (not its value); that a top-up or package was ordered through a Hub
  widget and whether it was paid and delivered (not the phone number, the
  product, or the price); that the question to turn usage data off was shown, which is
  sent before you answer it, and that it was kept on or turned on again (never
  that it was turned off: from then on nothing is sent). Each batch also carries the app version, the
  operating system version, and the device model (such as "Pixel 8").
- **What is never sent:** amounts, invoices, payment hashes, addresses,
  balances, keys, Lightning addresses, contact names, error messages, what a
  scanned code contains, or any identifier of you, your device, or your
  installation. The data cannot hold free text, so these cannot be included by
  accident. The complete list of what can be sent is
  [public](https://github.com/NicolaLS/rayl/blob/main/core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt).
- **How it is stored:** in a database we run with our hosting provider
  (Railway). We do not store your IP address with the data, and we record only
  the hour in which it arrived. Individual records are deleted after 90 days;
  daily totals and averages, without device models, are kept.
- **Who sees it:** only us. It is not shared with or sold to anyone, and it is
  not used for advertising.

Rayl 1.1 and earlier, and debug and test builds, do not send usage data.

## Feedback you choose to send

The Hub's "Add widget" screen offers a form to tell us which widget is missing
or to share an idea or problem. Nothing is sent unless you press **Send**. We
receive what you wrote, the kind of message you chose, whether you use Android
or iOS, the time it arrived, and the email or handle you typed if you chose to
add one. We do not receive your IP address, wallet, contacts, or payments with
it, so please do not write secrets in the message. It is stored in the same
database as the usage data above, readable only by us, and kept until we delete
it. Ask us at the address above to delete a message and we will, if you tell
us roughly when you sent it and what it said.

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
  If you set Rayl's wallet up without a passkey, Rayl creates the key on the
  device and stores it the same way; it is not sent anywhere or backed up.
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
- **PPQ.AI API key:** If you add a PPQ.AI widget to the Hub, the ppq.ai API key
  you enter is stored in encrypted app storage on Android or the Keychain on
  iOS. It is deleted when you remove the last PPQ.AI widget.
- **Claro phone number and orders:** If you add a Claro widget to the Hub, the
  phone number you enter is stored in local app storage with the widget and is
  removed with it. After you order through it, Rayl also keeps Claro's
  reference for the order there, for at most three days, until it has asked
  Claro whether the order was paid and delivered. It does neither if you
  turned anonymous usage data off.
- **Recent payments in Rayl:** Up to ten payment results shown in Rayl are kept
  in memory and are cleared when the app process ends or you change wallets.
  The wallet's own payment history is stored as described above.
- **Preferences:** Theme, language, display currency, payment-confirmation
  settings, and your usage-data choice are stored locally, together with
  markers that keep one-time events (such as "onboarding completed") from
  being sent twice.
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
- **Rayl backend:** Rayl requests aggregated Bitcoin exchange rates from the
  Rayl backend to show amounts in your display currency, and the last day of
  prices for the Hub's exchange rate widget. It also sends the backend the
  anonymous usage data described above unless you turned that off, and the
  feedback described above when you send it. These
  requests contain no wallet credentials, contacts, payment history, or user
  identifiers.
- **ppq.ai (PPQ.AI widget only):** If you add a PPQ.AI widget, Rayl sends your
  ppq.ai API key to ppq.ai to show your balance, and to request a Lightning
  invoice when you top up. ppq.ai processes your account under its own terms
  and privacy policy.
- **Claro El Salvador (Claro widget only):** If you add a Claro widget, Rayl
  sends the phone number you entered to Claro El Salvador to list its top-ups
  and packages, and together with the product you choose to request a Lightning
  invoice. Claro's payment provider, BTCPaths, issues that invoice, and Rayl
  contacts it to fetch it. Afterwards, unless you turned anonymous usage data
  off, Rayl asks BTCPaths whether the invoice was paid and Claro whether the
  order was delivered. Both process the order
  under their own terms and privacy policies.
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
  passkey as well, nobody can open the wallet or recover its funds. A wallet
  set up without a passkey can only be opened again with its recovery phrase,
  shown in Rayl's Settings, once its key copy is deleted.
- **Contacts and widgets:** Delete them in the Hub. Removing the last PPQ.AI
  widget deletes the stored API key from the device; it does not revoke the
  key at ppq.ai.
- **Everything else:** Clearing Rayl's storage on Android or deleting the app
  on iOS erases Rayl's local data.

Because the usage data is anonymous, we cannot tell which records came from
you, so we hold no data about you and usually have nothing to access, correct,
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
