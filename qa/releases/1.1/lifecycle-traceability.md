# Wallet lifecycle traceability (M6)

Each rule of [SPEC-wallet-lifecycle](../../../specs/SPEC-wallet-lifecycle.md), the tests that
protect it, and what is left. Traced on 2026-11-02 against `main`. Risks PR-08, PR-09, PR-12.

Levels: **H** host test (runs in the gate), **S** static check (read the code), **3** needs a
device or simulator (stage 3).

## External connections, erasure, interruption

| # | Rule | Protected by | Level | State |
|---|---|---|---|---|
| L1 | While Rayl's wallet is in use, no external session is active and no external credentials or data remain | `RaylSelectionTest.raylsWalletIsInUseAfterOnboardingUntilAnExternalWalletConnects`, `removalAndSwitchingRequireCleanupAndAlwaysStartFresh` | H | Covered |
| L2 | Choosing an external wallet starts a fresh setup that never reads earlier credentials or reconnects an earlier session | `BlinkWalletPaymentTest.freshSetupAndPaymentWithoutAConnectionNeverReadOldCredentials`, `NwcWalletConnectionTest.freshSetupAndForegroundNeverRestoreOldCredentials`, `WalletLifecycleTest.freshSetupNeverReadsCredentialsOnStartupRetryOrImport`, `freshSetupCannotResurrectAnUnexpectedExistingCredential` | H | Covered, all three wallets |
| L3 | Only a successful setup makes a wallet restorable | `RaylSelectionTest.abandonedSetupIsNotRestoredAndOrphanCredentialsAreErasedBeforeChoosing`, `lateConnectionEventsCannotResurrectARemovedWallet` | H | Covered |
| L4 | At most one external wallet owns a configured connection | `RaylSelectionTest.onlyTheConfiguredWalletIsRestoredAndOtherCredentialsAreErased`, `unknownStoredWalletIsNotRestored` | H | Covered |
| L5 | A relaunch restores the configured external wallet | `RaylSelectionTest.onlyTheConfiguredWalletIsRestoredAndOtherCredentialsAreErased` | H | Covered |
| L6 | A temporary connection or credential-access failure keeps the wallet and offers recovery; no silent return to Rayl, no erasure | Spark: `WalletLifecycleTest.credentialFailureCanBeRetriedAndRemovedWithoutDecrypting`, `WalletViewModelTest.credentialErrorActionsRetryConfirmAndReportRemovalFailure`. Blink, NWC: none | H / 3 | **Partly.** O-11 |
| L7 | Going back to Rayl stops the session and erases credentials and data before Rayl's wallet mounts | `RaylSelectionTest.removalAndSwitchingRequireCleanupAndAlwaysStartFresh`, `ConnectionStorageResetTest`, `WalletRemovalTest` | H | Covered (state machine and storage reset); the platform wiring is S |
| L8 | Cancelling setup releases the session and erases partial data | `WalletLifecycleTest.closeCancelsImportAndOwnsSessionWhoseAttachmentWasInterrupted`; app level via L3 (erased before the next choice) | H | Covered |
| L9 | A cleanup failure blocks switching and offers retry; erasure resumes after restart | `RaylSelectionTest.failedCleanupBlocksTheChooserAndCanBeRetriedAfterRestart`, `cancelledCleanupDoesNotReportSuccess`, `ConnectionStorageResetTest.interruptedErasureResumesWithoutClearingPreferencesOrAnotherProvider`, `WalletRemovalTest` (3 tests) | H | Covered |
| L10 | Startup clears unowned external data before mounting a wallet, including Keychain items that survive uninstall | `RaylSelectionTest.abandonedSetupIsNotRestored…` (state machine) | H / 3 | State machine covered; Keychain after reinstall is a release checklist item |
| L11 | An Android configuration change keeps the in-process setup | none | 3 | Release checklist (rotate during setup) |
| L12 | Reconnecting after going back requires the credentials again | follows from L2 and L7 | H | Covered |
| L13 | The same Spark recovery phrase identifies the same remote wallet | none | 3 | Mainnet protocol (remove, re-import, same balance) |
| L14 | An external Spark wallet is never the Rayl pocket | Static check below | S | Covered by separate storage |

## The Rayl pocket

| # | Rule | Protected by | Level | State |
|---|---|---|---|---|
| P1 | Rayl never generates the key; it comes from the passkey | `RaylPocketTest.needsAKeyInsteadOfCreatingOne`, `PocketSetupTest` | H | Covered |
| P2 | Without passkey support, onboarding offers the external wallets | `RaylOnboardingStateTest.withoutPasskeysTheUserIsSentToAnotherWallet` | H | Covered |
| P3 | The key copy is stored before the pocket first connects | `RaylPocketTest.installStoresTheKeyAndConnectsWithIt`, `doesNotConnectWithAKeyThatWasNotStored` | H | Covered |
| P4 | A key that exists but can't be read is never replaced silently | `RaylPocketTest.neverReplacesAKeyThatCannotBeRead`, `anUnreadableKeyStillCountsAsAKey`; Android Keystore: `AndroidPocketKeyStoreTest` (device) | H / D | Common code and Android Keystore covered; iOS Keychain carried over |
| P5 | Using another wallet stops the pocket without erasing its key or data | `RaylWalletExitTest.usingAnotherWalletClosesThePocketButKeepsItsKey`, `RaylPocketTest.closeKeepsTheKeyAndEraseDeletesIt` | H | **Covered (new, 2026-11-02)** |
| P6 | Startup cleanup doesn't erase the pocket | Static check below: cleanup only knows external wallets | S | Covered |
| P7 | Going back to Rayl restores the same pocket | P5 plus `RaylPocketTest.reusesTheStoredKey` | H | Covered |
| P8 | Only an explicit action erases the copy: a debug action, or setting up again when the copy can't be read | `RaylWalletExitTest.onlyADebugBuildCanEraseTheKey`, `settingUpAgainErasesTheKeyOnlyWhenItCannotBeRead`, `RaylOnboardingStateTest.resetRunsTheSetupAgain` | H | **Covered (new, 2026-11-02)** |

## Static checks (2026-11-02)

- **External cleanup can't reach the pocket (L14, P6).** The host's cleanup
  (`clearSavedWallet`, Android and iOS) takes only an `ExternalWallet` (Blink, NWC, Spark).
  Spark erases `filesDir/breez-spark` and `noBackupFilesDir/wallet-credential` on Android, and
  `Application Support/spark-wallet` plus Keychain service
  `xyz.lilsus.rayl.providers.spark.wallet` on iOS. The pocket lives in
  `noBackupFilesDir/rayl-pocket` and `Application Support/rayl-pocket` with Keychain service
  `xyz.lilsus.rayl.pocket`. Every Keychain delete is scoped to its service.

## Observation

**O-11 (confirmed on a real Android Keystore 2026-11-03, `SecureSettingsTest`; PR-08/PR-12).** On Android, the secure store returns "absent"
when a credential can't be decrypted (`EncryptedStringSettings.getStringOrNull` swallows the
error). Blink and NWC therefore can't tell an unreadable credential from a missing one: a
restored Blink or NWC wallet with an unreadable credential shows its setup screen rather than
a recovery message. It doesn't erase anything or go back to Rayl's wallet on its own, so it
may still meet L6. Spark distinguishes the two cases. Check on a device whether the Blink and
NWC screens are an acceptable "recovery".
