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
| 5 | `feature/payment-ui` | 15 → 10 | PR-01, PR-05 | Done | 10 | 0 | 5 |
| 6 | `feature/payment-currency`, `integration/exchange-rate`, `backend/rayl` | 18 → 17 | PR-01, PR-18 | Done | 16 | 1 improved | 1 |
| 7 | `integration/lnurl` | 7 | PR-05, PR-11 | Done | 4 | 2 merged into 1, 1 split into 2 | 0 |
| 8 | Settlement: `providers/rayl/feature/payment`, `providers/spark/feature/payment` and `SparkPaymentsTest`, `providers/blink/integration/blink`, `providers/nwc/integration/nwc` | 5, 6 + 3, 16, 3 → 31 | PR-04, PR-07, PR-08 | Done | 31 | 0 | 2 |
| 9 | Pocket: `providers/rayl/integration/pocket`, `providers/rayl/experience` | 46 → 38 | PR-09, PR-10 | Done | 38 | 0 | 8 |
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

### 5. `feature/payment-ui` (2026-11-01)

`ManualAmountController` turns keypresses into the amount that is paid. Its best tests are
real PR-01 guards; several others were redundant or exotic.

| Test | Verdict | Reason |
|---|---|---|
| `fiatStartsPrimaryAndSwitchesTheEnteredValueToWholeSats` | Keep | $1.00 at $60,000/BTC shows as 1,667 sats, the rounded-up whole sats that are paid |
| `roundTripPreservesExactSatsUntilTheUserEditsFiat` | Keep | A lossy round trip would turn 1 sat into $0.01 and back into 17 sats. Hard to read (C6), but it guards a real money bug. |
| `rateRefreshKeepsSatEntryAndNewEntryRestoresFiat` | Keep | A price refresh must never change the sats the user typed |
| `satoshiEntryWorksWithoutAnExchangeRate` | Keep | With the price service down (PR-18), paying in sats still works |
| `zeroDecimalFiatStaysIntegralInBothUnits` | Keep | The JPY partition (no minor unit) |
| `inclusiveSatoshiBoundsAcceptExactlyMinAndMax` | Keep | Boundary values of the LNURL range |
| `zeroAndClearedEntryShowNoErrorButPositiveAmountBelowMinDoes` | Keep | A zero or cleared entry yields no amount, so it can't be submitted |
| `fiatRangeUsesRoundedPaymentAmount` | Keep | The range check uses the rounded-up amount that is actually submitted, consistent with `LnurlInvoiceResolver` |
| `satoshiPreferenceHasNoCurrencySwitch` | **Delete** | Cosmetic: a key that does nothing |
| `satoshiSwitchValidatesBoundsAndClearsErrorWhenCorrected` | **Delete** | Redundant with the inclusive-bounds, zero/below-min and rounded-max tests (A4–A6 stay killed without it) |
| `positiveFiatBelowMillisatoshiPrecisionReportsTooLow` | **Delete** | Needs a rate of $10 billion per bitcoin. The resolver rejects a zero amount anyway. |
| `reportsBelowMinWhenUnderRange` | **Delete** | The same partition as the zero/below-min test |
| `LnurlPayDisplayTest.sanitizesUntrustedTextAndBoundsDescription` | Keep | PR-05: strips bidi overrides and markup that could spoof what is being paid for (A7 killed) |
| `LnurlPayDisplayTest.rejectsMissingRequiredTextAndOmitsInvalidImage` | Keep | Untrusted metadata: a missing description or invalid image is handled |
| `LnurlPayDisplayTest.fixedAmountReviewCarriesLnurlDetails` | **Delete** | A tautology: builds a data class and asserts its own constructor arguments |

**Mutation checks** (run after the deletions)

| # | Mutation | Result |
|---|---|---|
| A1 | The sats equivalent rounds down instead of up | Killed |
| A2 | A currency round trip doesn't restore the original entry | Killed |
| A3 | The range check uses unrounded msats | Killed |
| A4 | Below-minimum isn't reported | Killed |
| A5 | Above-maximum isn't reported | Killed |
| A6 | The minimum becomes exclusive | Killed |
| A7 | Bidi override characters are kept in LNURL descriptions | Killed |

**Gaps:** with a fiat currency and no price, a typed fiat amount is returned as an amount
with `RangeStatus.Unknown`. The flow must refuse to pay it; that belongs to the flow tests
(M2) and S2.

### 6. Prices: `feature/payment-currency`, `integration/exchange-rate`, `backend/rayl` (2026-11-01)

`PaymentCurrencyManager.quote` is the fiat→sats conversion for payments. The backend
aggregates prices from four public sources and serves the median.

| Test | Verdict | Reason |
|---|---|---|
| `PaymentCurrencyManagerTest.paymentQuoteUsesRateReturnedForThatAction` | **Improve** → `paymentQuoteFetchesARateInsteadOfReusingTheDisplayedOne` | It claimed each quote uses a fresh rate, but the manager never held a cached rate (display currency left at sats), so reusing a stale rate went unnoticed (Q3 survived). The display currency is now set first. |
| `PaymentCurrencyManagerTest.fiatQuoteFailsClosedWithoutRate` | Keep | PR-18: no price, no fiat payment |
| `PaymentCurrencyManagerTest.unknownFiatCurrencyIsNotTreatedAsSatoshis` | Keep | The catalog falls back to sats for unknown codes (module 4); without this guard, 500 "XYZ" would be paid as 500 sats |
| `RaylBitcoinPriceProviderTest.oneRequestServesEveryCurrencyByNormalizedCode` | Keep | The right price per currency, and the request URL the backend serves |
| `RaylBitcoinPriceProviderTest.coalescesConcurrentCacheMisses` | **Delete** | Only backend load (5 concurrent lookups → 1 request); no user-facing effect |
| `RaylBitcoinPriceProviderTest.refetchesAfterTheCacheExpires` | Keep | A stale price must be refreshed (PR-01); exact TTL boundary |
| `RaylBitcoinPriceProviderTest.unavailableBackendYieldsNoPrice` | Keep | A 503 yields no price, so fiat payments fail closed |
| `PriceBoardTest` (4 tests) | Keep | The median protects against one wrong source (B1 killed); invalid quotes are dropped; a failing source's quotes expire; no fresh source means no prices |
| `PriceSourcesTest` (5 tests) | Keep | Parsers for four external APIs from realistic samples (PR-19), including Kraken's error response |
| `RaylServerTest` (2 tests) | Keep | The 200 and 503 responses and their caching headers; a cached 503 or uncached prices would hurt every client |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| Q1 | `quote` accepts zero, NaN or infinite rates | **Survived**. Today most of these still fail safe further down, but NaN makes `roundToLong` throw. |
| Q2 | `quote` rounds down to whole sats instead of up | **Survived** |
| Q3 | `quote` reuses the displayed (possibly stale) rate | Survived → killed after the improvement |
| B1 | The backend serves the mean instead of the median | Killed |

**Gaps** (for stage 2):
- M5: rounding to whole sats in `quote` (Q2), with a non-exact amount such as $1.00 at
  $60,000 → 1,667 sats.
- S2: invalid rates (0, negative, NaN, infinite) yield no quote (Q1). A golden JSON for the
  prices response: the server and client share one model, so they can't drift within a
  build, but already released apps are frozen while the backend keeps changing.

### 7. `integration/lnurl` (2026-11-01)

The best tests in the suite: they treat the LNURL server as hostile. They cover SSRF
(private, loopback, NAT64, `.onion` and `.local` destinations, and DNS answers that resolve
to loopback), refused redirects, response size and nesting limits, strict field validation,
and redaction of remote error text.

| Test | Verdict | Reason |
|---|---|---|
| `lightningAddressRequestPreservesUsernameAndTagCase` | Keep | The address becomes the right `.well-known` URL |
| `payParamsRequestPropagatesCancellation`, `invoiceRequestPropagatesCancellation` | **Merge** → `cancellationIsPropagatedInsteadOfBecomingAnError` | Both entry points share one `request {}` wrapper. A swallowed cancellation would surface as an LNURL error after the session was cancelled (N2 killed). |
| `rejectsUnsafeDestinationsAndDnsAnswersBeforeRequesting` | Keep | SSRF protection, including DNS rebinding |
| `rejectsRedirectsAndOversizedResponsesWithoutTrustingContentLength` | Keep | Resource limits against a hostile server |
| `rejectsMalformedFieldsAndMetadata` | Keep | Strict LUD-06 validation, including a non-public callback (N4 killed) |
| `replacesCallbackParametersAndRedactsRemoteFailures` | **Improve** → split into `invoiceRequestSendsOnlyTheApprovedAmountAndComment` and `remoteFailureReasonsAreNotShown` | Two behaviours in one test, and the amount check ran *inside* the mock engine. The client turned the thrown assertion into an `Unexpected` error, so the amount check only worked through the later error comparison. It now captures the request and asserts outside. |

**Mutation checks** (after the changes)

| # | Mutation | Result |
|---|---|---|
| N1 | A server-supplied `amount` in the callback URL is kept | Killed (directly) |
| N1b | A server-supplied `comment` is kept | Killed |
| N2 | Cancellation becomes an error result | Killed |
| N3 | The explicit redirect check is removed | Survived: equivalent mutant, since a 3xx still fails the "not 2xx" check |
| N4 | The callback destination isn't checked | Killed |
| N5 | The server's error reason is shown to the user | Killed |

**Gaps:** none at this level.

### 8. Settlement in each wallet (2026-11-01)

**Headline finding:** PR-04's central rule, *an outcome that is uncertain after
submitting is `Unknown`, never `Failed`*, is not protected in any wallet. In all four
settlements, turning a thrown exception into `Failed` goes unnoticed (S1–S4 below).
`BlinkPaymentSettlement` and `NwcPaymentSettlement` have no tests at all, and the Rayl and
Spark tests never throw during `settle`. A "failed" message after a payment that actually
went through invites the user to pay again. This makes the settlement contract suite (M3)
the first stage 2 item.

| Test | Verdict | Reason |
|---|---|---|
| `RaylPaymentSettlementTest` (5) and `SparkPaymentSettlementTest` (6) | Keep, to be replaced by M3 | Near-identical tests for two mirrored implementations: whole-sat quoting for amountless invoices, refusing sub-sat amounts instead of truncating them, insufficient funds before confirmation, outcome and refusal mapping. Not redundant (different code), but exactly what one shared contract suite should cover. |
| `SparkPaymentsTest.quotesNeedAnAttachedSession` | **Delete** | Redundant with `SparkPaymentSettlementTest.aDisconnectedWalletPaysNothing`, which still kills the mutation |
| `SparkPaymentsTest.paysThroughTheSessionThatQuoted` | Keep | The only check that the quoted fee survives the session wrapper; fee-aware Auto Pay (SI-01) will depend on it |
| `SparkPaymentsTest.aQuoteFromAnEarlierSessionIsNeverSubmitted` | Keep | A quote must never be paid through a reconnected session |
| `BlinkWalletPaymentTest` (13) | Keep | The strongest settlement tests: a pending payment stays unconfirmed and is never resubmitted; a failed status query never becomes "failed"; the confirmed funding wallet is used; a USD wallet pays amountless invoices in cents; a fresh setup never reads old credentials |
| `BlinkApiClientTest.fetchAuthorizationScopesReturnsScopes` | **Delete** | Checks that Apollo parses a JSON list (library behaviour) |
| `BlinkApiClientTest.payInvoiceParsesTransaction` | Keep | Blink reports the fee as a negative settlement amount; it must become a positive fee in msat (PR-06) |
| `BlinkCredentialStoreTest.storesAndClearsOneCredentialSet` | Keep | Clearing must remove the credentials (PR-08) |
| `NwcWalletConnectionTest` (3) | Keep | A regression test for a real lifecycle race (introduced with `08b9ed15`, "enforce single-wallet lifecycle"); a fresh setup never reads old credentials; a connection without `pay_invoice` or `lookup_invoice` can't be saved |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| S1 | Rayl: an exception during `settle` becomes `Failed` | **Survived** |
| S2 | Spark: the same | **Survived** |
| S3 | NWC: the same | **Survived** (no tests) |
| S4 | Blink: the same | **Survived** (no tests) |
| S5 | Blink: `StatusUnknown` maps to `Failed` | **Survived** (no tests) |
| S6 | Spark: a missing session returns "insufficient funds" instead of "not ready" | Killed (after the deletion) |
| S7 | Spark: a quote from an earlier session is submitted | Killed |
| S8 | Rayl: a sub-sat amount is truncated | Killed |

**Gaps** (for stage 2):
- **M3 (first):** one contract suite run against all four settlements: thrown or uncertain
  after submit → `Unknown`; `prepare` failures → not paid; cancellation propagates;
  already-paid, expired and insufficient funds → shared errors; fee reported in msat.
- NWC outcome classification (`WalletRejected`, `DefinitelyNotSent`, `Uncertain`) and
  Blink's USD funding amount: untested.

**Observation O-09** (PR-15, to the stakeholder): rescanning an already-paid invoice gives
different results per wallet. Blink returns `Paid(wasAlreadyPaid = true)`, a success screen
saying it was already paid. Rayl and Spark return `Failed(AlreadyPaid)`, an error screen.
REQ-provider-experience asks for the same experience. Which one is intended?

**Tool fix:** `mutate.sh` looked for test results in the mutated file's module. When the
test task belongs to another module, it misreported the result as a build failure. It now
derives the results directory from the task path. No earlier result was affected: they all
used the same module for both.

### 9. Pocket: `providers/rayl/integration/pocket`, `providers/rayl/experience` (2026-11-01)

The pocket tests encode PR-09's rules almost word for word, and the passkey setup tests
decide *which key is stored*, i.e. which wallet and whose funds the user sees. Most are high
value. The deletions are a test of the fake, dev-only code, and cosmetic state.

| Test | Verdict | Reason |
|---|---|---|
| `RaylPocketTest.installNeverReplacesAStoredKey` | **Delete** | It tests the fake. `RaylPocket.install` only calls `keys.add`; the never-replace rule lives in the store's contract, which the fake implements. The real stores are platform code (Android Keystore plus file, iOS Keychain): see the residual risk below. |
| `RaylPocketTest` (other 10) | Keep | Never create, never overwrite an unreadable key (K1 killed), never connect with an unstored key, retry with the same key, close keeps the key and erase deletes it, a payment during startup waits for the pocket, received payments update the balance |
| `PocketSetupTest` (14) | Keep | Account selection on a passkey: the default wallet only when there are no accounts, a relay failure fails instead of opening the default wallet (K4 killed), the chosen account is opened on the same passkey, the no-PRF paths never create a passkey by accident |
| `FilePrfProviderTest` (4) | **Delete** | `FilePrfProvider` is a stand-in used only without a relying party, i.e. debug and emulator builds; release builds set one on both platforms. It protects developer regtest wallets, and a break shows immediately on an emulator. |
| `PaymentProblemTest.recognizesTheRefusalsBreezDocumentsByTypeOrText` | Keep | Decides which SDK errors are definite refusals (`Failed`) and which may have paid (`Unknown`). Partly matched by message text, so a Breez SDK upgrade can break it silently (PR-19). |
| `PaymentProblemTest.detailDropsTheBindingsFieldPrefix` | **Delete** | Message formatting only |
| `RaylFundingTest.anInvoiceIsFirstOfferedToAnotherWalletApp` | **Delete** | A cosmetic presentation transition |
| `RaylFundingTest` (other 4) | Keep | Add funds: the invoice is shown when no wallet app can open it, only a payment to *this* invoice counts (F1 killed), each request gets a new invoice, a failure can be retried |
| `RaylOnboardingStateTest.aDismissedPromptWaitsForAnotherTap` | **Delete** | UI state only; that a dismissed prompt stores nothing is covered by `PocketSetupTest.aDismissedPromptOnlyCancels` |
| `RaylOnboardingStateTest` (other 9) | Keep | Terms before the passkey (PR-21), completion is remembered (no second passkey setup), the fallback to external wallets, account choice and creation, failure and retry, the no-PRF prompt, reset for recovery |

**Mutation checks**

| # | Mutation | Result |
|---|---|---|
| K1 | An unreadable key is treated as absent | Killed |
| K2 | `install` starts the pocket even when the key wasn't stored | Survived: equivalent, since `connect` always reads the key from the store |
| K3 | `hasKey` treats an unreadable key as no key | **Survived** |
| K4 | A relay failure while listing accounts falls back to the default wallet | Killed |
| F1 | Funding counts a payment to another invoice | Killed |

**Gaps** (for stage 2, M7):
- K3: with an unreadable key, onboarding would run passkey setup again instead of offering
  recovery, then fail with "the key could not be stored". Nothing is lost (the store
  refuses), but the recovery path is broken.
- `BreezPocketWallet.pay` classifies SDK errors after submitting (network and unknown →
  `Unknown`). It depends on the Breez SDK class directly, so testing it needs a seam or the
  regtest level.

**Residual risk (stage 3):** the rule that a stored pocket key is never replaced is
enforced only by `AndroidPocketKeyStore` and the iOS Keychain store. No host test can check
it. Until platform tests exist (P2.2), the release checklist must cover reinstall and key
restore on both platforms.

