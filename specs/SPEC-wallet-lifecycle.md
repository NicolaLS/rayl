# SPEC-wallet-lifecycle: Rayl's wallet and one external connection

## Record justification

Rayl's platform hosts, provider runtimes, and platform credential stores jointly implement connection ownership and erasure; none alone can ensure that no external wallet connection is retained after the user goes back to Rayl's wallet.

## Rayl's wallet and external wallets

Rayl pays with its built-in wallet, the Rayl pocket, unless the user connects an
external wallet (Blink, NWC, or Spark) from its Settings. After the welcome,
Rayl's wallet is in use once its terms are accepted and its key is set up.
App preferences, the user's shared Hub, and the pocket are independent of
external connections.

## External connections

While Rayl's wallet is in use, no external provider session is active and no
external provider retains credentials or connection-specific local data.

Choosing an external wallet closes Rayl's wallet session and starts temporary
setup. It must not retrieve an earlier wallet's credentials or reconnect an
earlier session. Only successful connection setup makes that wallet eligible for
restoration on later app launches. At most one external provider may own a
configured connection.

An ordinary relaunch restores the configured external wallet. A temporary
connection or credential-access failure preserves that ownership and offers
recovery; it does not silently go back to Rayl's wallet or erase the connection.

## Erasure and interruption

Going back to Rayl stops the external session and erases its credentials and
connection data before Rayl's wallet mounts. Cancelling setup also releases its
session and erases any partial connection data. A cleanup failure blocks
switching and offers retry; erasure resumes after restart.

Startup clears unowned external provider data before mounting any wallet.
Interrupted, uncommitted setup and a fresh installation therefore start with
Rayl's wallet and without a saved connection, including Keychain items that
survive uninstall. An Android configuration change retains the current
in-process setup.

Connecting the same wallet after going back to Rayl requires supplying its
credentials again. Importing the same Spark recovery phrase still identifies the
same remote wallet; local erasure does not create a new wallet or move funds.
An external Spark wallet is never the Rayl pocket, even when both are Spark
wallets.

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
phrase, which the external Spark wallet or any other Spark wallet can import.
The key is never entered into the pocket, and a stored key keeps its origin
for as long as it exists.

The device keeps a copy of the derived key so launches and payments never
prompt. The copy is stored before the pocket first connects, and a key that
exists but cannot be read is never replaced silently. Using another wallet stops
the pocket session without erasing the key copy or pocket data; startup cleanup
does not erase them either, and going back to Rayl restores the same pocket.
Only an explicit user action erases the copy, after which the pocket is set up
again: a debug-build action, or, in any build, setting Rayl's wallet up again
from its passkey when its copy exists but cannot be read. A key generated
without a passkey has no other copy, so users are never offered to erase it,
readable or not.
