# Audit of the existing automated tests

Stage 1 of the [1.1 release test plan](release-test-plan.md#31-current-focus-host-level-tests-only-decided-2026-11-01).
The existing tests were written without a strategy. This audit reviews every one of them,
so that the suite we keep is small, honest and worth running on every change.

Baseline: 254 tests in 63 classes ([test log](test-log.md), `c3c00e81`).

## Criteria

A test earns its place only if it would **catch a realistic bug in behaviour that matters**.
Each test is checked against these questions:

| # | Criterion | Typical failures |
|---|---|---|
| C1 | **Catches bugs.** Would a plausible change to the code under test make it fail? | No assertion, or a weak one (`assertNotNull`, "doesn't throw"); asserts what the test itself set up; asserts on a fake, not on the code under test; the expected value is computed with the same logic as the code |
| C2 | **Tests our behaviour, and behaviour that matters.** | Tests a library, the platform or the Kotlin language; trivial getters, data class `equals`, constants; behaviour no user or caller depends on |
| C3 | **Tests behaviour, not implementation.** Uses the public API and checks observable results. | Checks private call order or internal state; breaks on a harmless refactor; pins exact log text or incidental formatting |
| C4 | **Correct oracle.** The expected result comes from the requirement, spec or an independent source. | The expected value was copied from what the code printed, which locks bugs in |
| C5 | **Deterministic and isolated.** | Real time, `delay`, real network, shared mutable state, order dependence |
| C6 | **Readable.** The name states the behaviour; arrange/act/assert is clear; a failure tells you what broke. | `test1`, several behaviours in one test, huge setup that hides the point |
| C7 | **Not redundant.** It adds a partition or boundary that no other test covers. | The same partition checked again in another test or module |
| C8 | **Worth its cost.** | Heavy fixtures or fakes out of proportion to the value; slow |

When a test's bug-catching power is doubtful (C1), it is checked by **mutation**: change the
code under test in a plausible way and see whether the test fails. The change is always
reverted, and the result is noted in the module's row.

## Verdicts

| Verdict | Meaning |
|---|---|
| **Keep** | Meets the criteria as it is |
| **Improve** | Valuable, but has a specific fixable flaw (named in the notes) |
| **Merge** | Combine with another test, e.g. into one table-driven test |
| **Replace** | The behaviour matters but the test checks it badly; rewrite it |
| **Delete** | Fails C1 or C2 and can't be fixed cheaply, or is fully redundant (C7). The reason is always recorded. |

**The bar is deliberately high** (Nicola, 2026-11-01): only high-quality tests that clearly
make sense stay. Passing the criteria is necessary, not sufficient. A test that checks
cosmetic or low-value behaviour, or costs more attention than the bug it could catch is
worth, is deleted even if it works.

The audit also records **gaps**: important behaviour of the audited code that no test
covers. Gaps feed stage 2. They are not filled during the audit.

## Process

One module per slice:
1. Read the tests and the code they test.
2. Give each test a verdict, with mutation checks where C1 is doubtful.
3. Record the verdicts and gaps below.
4. Apply the verdicts. Production code is not changed; a suspected defect becomes a defect
   report instead.
5. Run the module's tests and ktlint, then commit as one slice.

Modules are audited in risk order: Level 1 modules first.

## Progress

| # | Module | Tests | Risks | Status | Kept | Improved / merged / replaced | Deleted |
|---|---|---|---|---|---|---|---|
| 1 | `feature/payment-flow` | 8 → 9 | PR-02–PR-04 | Done | 6 | 1 improved (split into 3) | 1 |
| 2 | `feature/payment-settings` | 9 → 8 | PR-02 | Done | 0 | 1 rewritten (policy, `8b4855be`), 1 replaced, 1 improved | 1 |
| 3 | `core/payment` | 26 → 25 | PR-05, PR-14 | Done | 25 | 0 | 1 |
| 4 | `core/model` | 10 → 8 | PR-01 | Done | 8 | 0 | 2 |
| 5 | `feature/payment-ui` | 15 | PR-01 | Not audited | | | |
| 6 | `feature/payment-currency`, `integration/exchange-rate`, `backend/rayl` | 3, 4, 11 | PR-01, PR-18 | Not audited | | | |
| 7 | `integration/lnurl` | 7 | PR-05 | Not audited | | | |
| 8 | Settlement: `providers/rayl/feature/payment`, `providers/spark/feature/payment`, `providers/blink/integration/blink`, `providers/nwc/integration/nwc` | 5, 6, 16, 3 | PR-04, PR-07 | Not audited | | | |
| 9 | Pocket: `providers/rayl/integration/pocket`, `providers/rayl/experience` | 31, 15 | PR-09, PR-10 | Not audited | | | |
| 10 | Spark wallet: `providers/spark/application`, `integration/wallet`, `feature/wallet-connection`, `experience` | 26, 4, 5, 3 | PR-08 | Not audited | | | |
| 11 | Selection and storage: `app/shared`, `core/settings` | 13, 1 | PR-08, PR-12 | Not audited | | | |
| 12 | Blink and NWC onboarding: `blink/experience`, `blink/feature/blink-contacts`, `nwc/experience`, `nwc/feature/onboarding` | 5, 2, 3, 3 | PR-08, PR-15 | Not audited | | | |
| 13 | Level 3 areas: `feature/payment-hub`, `core/camera`, `core/ui`, `feature/app-shell`, theme, currency and language settings | 10, 4, 1, 2, 6 | PR-24 and others | Not audited | | | |

## Module findings

Each audited module adds a section here: a table of test → verdict → reason, mutation
checks, and gaps.

### 1. `feature/payment-flow` (2026-11-01, pilot)

The 8 tests cover only three small classes: `PaymentHistory`, `PaymentOperation` and
`PaymentPresentationPhase`. They are good tests: focused on behaviour that matters,
deterministic (virtual time), with meaningful assertions. One weakness was found by
mutation.

| Test | Verdict | Reason |
|---|---|---|
| `PaymentHistoryTest.recentKeepsTenResolvedReceiptsAndOnlyPaidInvoicesPreventResubmission` | **Improve** → split into `onlyPaidInvoicesAreFoundForResubmissionChecks`, `keepsOnlyTheTenMostRecentPaymentsNewestFirst`, `resetForgetsEarlierPaymentsForTheNextSession` | Three behaviours in one test (C6). The reset check missed retained receipts (M7 survived): it only looked at the display list and lookup, which are empty after reset even when the receipts are still stored. The new reset test records a payment after the reset. |
| `PaymentHistoryTest.currencyChangesKeepSatsAndReceiptDetails` | Keep | Receipts must switch display currency without losing sats, recipient or proof. The fiat values depend on `core/model`'s rule that a non-zero amount never displays as zero; that coupling is acceptable. |
| `PaymentHistoryTest.alreadyPaidWithoutOriginalSettlementDoesNotInventAReceipt` | Keep | PR-04: an already-paid invoice must not show a receipt the user never paid (M5 killed) |
| `PaymentOperationTest.busyOperationRejectsOverlappingInputWithoutQueuingOrReplacingIt` | Keep | PR-03: the duplicate-payment guard (M1 killed) |
| `PaymentOperationTest.cancelledOperationCannotReleaseTheGateForANewerOperation` | Keep | PR-03: a late completion of a cancelled operation must not open the gate (M2 killed) |
| `PaymentPresentationPhaseTest.dismissingAResultCannotDismissLoading` | Keep | PR-04: a dismiss tap during payment must not hide the in-flight state (M4 killed) |
| `PaymentPresentationPhaseTest.transactionNavigationMarksOnlyTheSelectedTransactionAsSeen` | **Delete** (revised under the higher bar) | Only the new-transaction badge count and a navigation flag: cosmetic. It was first kept as cheap. |
| `PaymentPresentationPhaseTest.resetClearsTransientPresentationWithoutRememberingOldTransactions` | Keep | A session reset (e.g. a wallet switch) must not carry state over |

**Mutation checks** (`qa/tools/mutate.sh`; each reverted):

| # | Mutation | Result |
|---|---|---|
| M1 | `PaymentOperation.launch` no longer ignores input while busy | Killed |
| M2 | A completed operation clears the gate even if it was replaced | Killed |
| M3 | Failed receipts count as paid in the resubmission lookup | Killed |
| M4 | `dismissResult` also dismisses Loading | Killed |
| M5 | Already-paid results get a receipt | Killed |
| M6 | History keeps 11 instead of 10 | Killed |
| M7 | `reset` keeps stored receipts | **Survived** → killed after the improvement |

**Gaps** (for stage 2; about 1,000 of the module's 1,480 lines have no tests):
- `PaymentFlowCoordinator`, `PaymentAdmissionPhase` (495 lines), `PaymentConfirmationPhase`
  and `PaymentPreparation`: the whole scan → admit → confirm or Auto Pay → settle → outcome
  path (M1 through the flow, M2, M4).
- `PaymentErrorMapping`: how settlement errors become user-facing errors. This is where an
  uncertain outcome could be shown as a failure (PR-04, M3).

### 2. `feature/payment-settings` (2026-11-01)

| Test | Verdict | Reason |
|---|---|---|
| `PaymentConfirmationPolicyTest` (3 tests) | **Replaced** by a 6-test decision table in `8b4855be`, before this audit | The 3 tests were rows of the table; the table adds every combination, the msat boundary at each threshold step, and stored thresholds above the maximum |
| `PaymentPreferencesRepositoryTest.mutationPersistsOnlyTheChangedPreference` | **Replace** → `confirmationSettingsSurviveAnAppRestart` | It pinned which storage keys a write touches (C3, implementation detail). What matters for PR-02 was untested: that "Always confirm" and the threshold survive a restart. P1 and P2 below would have passed the old suite. |
| `PaymentPreferencesRepositoryConcurrencyTest.serializesConcurrentPreferenceWrites` | **Delete** | Real threads and `Thread.sleep` (C5, slow and potentially flaky), racing a mutex for a scenario that doesn't occur: every caller writes from the main dispatcher, and the write path has no suspension point. |
| `PaymentSettingsViewModelTest.updatesSharedPreferencesAndThresholdPreview` | **Improve** → `thresholdIsPreviewedInTheDisplayCurrency` | The fiat preview is what users read when choosing the Auto Pay limit, and the oracle is independent ($60k/BTC: 10,000 sats = $6.00). The `offerToSaveNewTargets` part was trivial delegation and is removed. |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| P1 | A stored "always" mode is read back as the default (Above) | Killed by the new restart test |
| P2 | The stored threshold is ignored on load | Killed by the new restart test |
| P3 | The preview isn't recomputed when the threshold changes | Killed |

**Gaps:** none worth a test now. The stale-price guard in the preview (a slow price for the
previous currency must not overwrite the new one) only affects display.

### 3. `core/payment` (2026-11-01)

The LNURL tests are the strongest in the suite so far: they pin security rules (https only,
no credentials or fragments in the URL, no IP-address hosts, bech32m and wrong prefixes
rejected) and the invoice checks that protect against a hostile server (amount, metadata,
expiry, range). The input parser tests are sound but thin: they assert only the target
type, and miss the partitions most common in real QR codes.

| Test | Verdict | Reason |
|---|---|---|
| `LightningInputParserTest` (5 tests) | Keep | Cover the routing of raw and `lightning:` BOLT11, LUD-17, BIP21 and LUD-01 fallbacks, and the unsupported formats. In stage 2 they become rows of the S1 input corpus, which adds the missing partitions (L1–L5). |
| `OrderedPaymentInputParserTest` (3 tests) | Keep | The priority contract of the parser composition, which Spark extends: first match wins, a redirect restarts at the top, unsupported recognisers never shadow payable input. |
| `LnurlParserTest` (9 tests) | Keep | Security-relevant parsing rules with independent oracles (LUD-01/16/17). `rejectsNonCanonicalLightningAddresses` pins a behaviour that awaits a product decision (O-08). |
| `LnurlInvoiceResolverTest.returnsClientError` | **Delete** | Restates a one-line pass-through of the client error. |
| `LnurlInvoiceResolverTest` (other 8) | Keep | PR-05 and PR-01: the invoice an LNURL server returns must match the requested amount and metadata and must not be expired; out-of-range amounts are refused before any request. |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| R1 | An invoice's `description_hash` is never compared with the metadata | **Survived** |
| R2 | An invoice for a different amount is accepted | Killed |
| R3 | An expired invoice is accepted | Killed |
| R4 | An amountless invoice from an LNURL server is accepted | **Survived** |
| L1 | Uppercase BOLT11 (common in QR codes) is not recognised | **Survived** |
| L2 | An uppercase `LIGHTNING:` scheme is not recognised | **Survived** |
| L3 | Whitespace around scanned input is not trimmed | **Survived** |
| L4 | A BIP21 URI with two `lightning=` values pays the first | Survived (minor) |
| L5 | A Lightning address is returned as a generic LNURL target (the Hub then can't offer to save it) | **Survived** |

**Gaps** (for stage 2):
- **PR-05, S4 (hostile LNURL):** R1 and R4. Real LNURL servers mostly bind the invoice
  with `description_hash`, so the path that matters most in production is the untested one.
- **PR-14, S1 (input corpus):** L1, L2, L3, L5, plus Lightning addresses (none of the parser
  tests use one) and a never-throws property test.

**Observation O-08** (to the stakeholder): `Alice@example.com` is rejected as unrecognised.
`LightningAddress.parse` accepts it, but the LNURL parser's own user-part check rejects
uppercase. Phone keyboards capitalise the first letter, so this will happen. LUD-16 allows
only lowercase, but many wallets lowercase the user part instead of rejecting it.

### 4. `core/model` (2026-11-01)

This module converts msats into *display* amounts. The money-critical direction (a fiat
amount into sats) is in `feature/payment-currency` (module 6).

| Test | Verdict | Reason |
|---|---|---|
| `AmountConversionTest` (4 tests) | Keep | The amounts shown on confirmation and receipts. Independent oracles (0.5 BTC × $60,000 = 3,000,000 cents); covers the missing-price case and the rule that a non-zero amount never shows as zero. |
| `CatalogTest.currencyLookupIsCaseInsensitive` | **Delete** | A trivial map lookup; a failure would only show sats instead of a fiat currency |
| `CatalogTest.languageLookupIsCaseInsensitive` | **Delete** | Trivial and cosmetic |
| `CatalogTest.unknownCurrencyFallsBackToSatoshis` | Keep, renamed `aStoredCurrencyNoLongerInTheCatalogFallsBackToSatoshis` | A stored currency code that leaves the catalog must not break startup |
| `LightningAddressTest.parsesSupportedUserInputAndRejectsNonAddresses` | Keep | A compact table of accepted and rejected forms, used by the Hub. It accepts `LiLsUs+Tips@BliNk.sv`, which the payment parser rejects (O-08). |
| `PaymentPreferencesTest` (2 tests) | Keep | Threshold clamping (PR-02), and the slider step shown for a stored threshold, with the boundary at 10,000 / 10,001 |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| C1 | Fiat amounts round down instead of to the nearest minor unit | Survived (display only, at most one minor unit) |
| C2 | The non-zero minimum is removed | Killed |

**Gaps:** a zero-decimal currency (JPY is in the catalog) and the rounding mode (C1). Both
are display-only and minor; they belong with M5.

