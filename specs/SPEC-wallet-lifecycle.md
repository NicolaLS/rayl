# SPEC-wallet-lifecycle: The pocket and the cards

## Record justification

Rayl's platform hosts, provider runtimes, and platform credential stores jointly implement card ownership and erasure; none alone can ensure that a removed or abandoned card leaves no credentials behind while every other card keeps its own.

## The pocket and the cards

Every user has exactly one Rayl pocket, Rayl's built-in wallet, shown to users
simply as "Rayl", and any number
of cards: external wallets (Blink, NWC, or Spark) the user added in Settings,
including several of one provider. The pocket is always present and can never
be removed; cards are added and removed freely. Exactly one wallet pays at a
time, and only it has a session. After the welcome, the pocket pays once its
terms are accepted and its key is set up. App preferences and the user's shared
Hub are the same whichever wallet pays.

The pocket is defined by ownership, not technology: Rayl sets its key up and
the user never enters it. A card is credentials the user brought. A Spark card
is never the pocket, even when both are Spark wallets, and nothing in Rayl
treats a card differently because of where its credentials came from.

## Cards

Each card keeps its credentials and connection data in storage of its own; no
card reads or erases another card's, and a card's name never decides its
storage. Rayl 1.1's single external wallet carries over as a card on the
storage 1.1 wrote.

Changing the paying wallet first closes the session of the wallet that pays,
then mounts the chosen one. Adding a card starts fresh setup, which must not
retrieve earlier credentials or reconnect an earlier session. Only successful
setup makes it a card; it then pays. A card being added whose wallet is
already the pocket or another card, as far as the wallets identify themselves
(Spark and the pocket by identity key, NWC by wallet key), is turned back: its
storage is erased, the existing wallet pays, and the user is told so once. An ordinary relaunch restores the wallet
that paid. A temporary connection or credential-access failure keeps the card
and offers recovery; it never silently switches wallets or erases the card.

## Erasure and interruption

Removing a card erases its credentials and connection data and never asks for
confirmation: the funds stay in that wallet. Removing the paying card also stops
its session, and the pocket pays. Cancelling setup releases its session, erases
its partial data, and returns to the wallet that paid before. A cleanup failure
blocks changing wallets and offers retry; erasure resumes after restart.

Startup erases storage no card owns before mounting any wallet. Interrupted,
uncommitted setup therefore never becomes a card, and a fresh installation
starts with the pocket and no cards, including Keychain items that survive
uninstall. An Android configuration change retains the current in-process setup.

Adding a removed wallet again requires its credentials again. Importing the
same Spark recovery phrase still identifies the same remote wallet; local
erasure does not create a new wallet or move funds.

## The Rayl pocket

The pocket holds funds in an app-owned wallet rather than a connection to an
external wallet. Breez passkey login derives its key from a passkey during
Rayl's wallet onboarding, so the passkey is the pocket's only backup and that
key is never entered, shown, or exported.

Only where no passkey can derive a key does Rayl generate one on the device:
when the device has no passkey support, or by the user's choice after their
password manager could not derive a key. Onboarding never offers this before a
passkey attempt has failed that way. The device then holds the only copy of the
key. Rayl neither backs it up nor asks the user to; Settings shows its recovery
phrase, which a Spark card or any other Spark wallet can import. Onboarding
only ever creates or opens the pocket: the key is never entered into the
pocket, and a stored key keeps its origin for as long as it exists.

The device keeps a copy of the derived key so launches and payments never
prompt. The copy is stored before the pocket first connects, and a key that
exists but cannot be read is never replaced silently. Paying with a card stops
the pocket session without erasing the key copy or pocket data; startup cleanup
does not erase them either, and paying with the pocket again restores the same
pocket. Only an explicit user action erases the copy, after which the pocket is
set up again: a debug-build action, or, in any build, setting Rayl's wallet up
again from its passkey when its copy exists but cannot be read. A key generated
without a passkey has no other copy, so users are offered to erase it only when
this device can no longer read it, after a confirmation, to start a new pocket;
the old pocket then opens only with its recovery phrase.
