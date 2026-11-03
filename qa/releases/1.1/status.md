# 1.1 test status

Updated: 2026-11-03. Plan: [release-test-plan.md](release-test-plan.md).
Executions: [test-log.md](test-log.md).

## Work items

Current focus: stages 1 and 2, host-level tests only ([plan §3.1](release-test-plan.md#31-current-focus-host-level-tests-only-decided-2026-11-01)).

| # | Item | State | Artifacts |
|---|---|---|---|
| A | Stage 1: audit the existing tests | **Done** (254 → 206 tests, after the Linear cross-check) | [test-audit.md](test-audit.md) |
| B | Stage 2: minimal host-level suite | In progress, see the stage 2 plan below | |
| M1 | Auto Pay decision table | Done (policy and through the flow); fee conditions come with MOB-93, after 1.1 | `PaymentConfirmationPolicyTest`, `PaymentFlowCoordinatorTest` |
| M2 | Payment flow component tests | Done (2.8): camera, manual entry and Hub preset sources; Paid, Failed, Unknown, unavailable and expired outcomes | `PaymentFlowCoordinatorTest` (12) |
| M3 | Settlement classification, four wallets | Done: all four wallets (2.1, 2.2), plus already-paid as success (MOB-90) | `RaylPaymentSettlementTest`, `SparkPaymentSettlementTest`, `BlinkPaymentSettlementTest`, `NwcPaymentSettlementTest` |
| M4 | Duplicate payment, O-02 | Host level done: in-flight guard and paid-invoice rescan through the flow (2.8). O-02 parked for stage 3. | `PaymentFlowCoordinatorTest` |
| M5 | Conversion boundaries | Fiat quote rounding done (2.7); amount entry was already covered (audit §5) | `PaymentCurrencyManagerTest` |
| M6 | Wallet lifecycle traceability | Done at host level (2026-11-02): 22 rules traced; gap P5/P8 closed with `RaylWalletExitTest`; L6 partly (O-11); L10, L11, L13 go to the checklist and mainnet protocol | [lifecycle-traceability.md](lifecycle-traceability.md) |
| M7 | Pocket key rules | Host-level part done (2.4, and when the copy may be erased: `RaylWalletExitTest`); the platform stores need stage 3 | `RaylPocketTest`, `RaylWalletExitTest` |
| M8 | Static reviews (secrets, release config, SDK pins) | Done (2026-11-02): MOB-95 fixed, `verifyBreezSdkPins` added to the gate, version bump for the 1.1 cut goes on the checklist | [static-review.md](static-review.md) |
| M9 | `distribution/` review | Done (2026-11-02): all four documents still described 1.0. Privacy, terms and store listings rewritten for 1.1 (2026-11-03: MOB-96, MOB-97, MOB-98 fixed; wording to be reviewed by Nicola); the in-app links were broken (MOB-102, fixed). MOB-99 (iOS encryption reassessment) stays open but is not a blocker (Nicola, 2026-11-03). | [distribution-review.md](distribution-review.md) |
| M10 | Release checklist and App Live run | Checklist drafted; run needs Nicola (App Live) | [release-regression.md](../../checklists/release-regression.md) |
| M11 | Mainnet protocol and run | Protocol drafted (1,000 sats); run needs Nicola (funded wallets) | [mainnet-acceptance.md](../../checklists/mainnet-acceptance.md), [stub-assumptions.md](../../stub-assumptions.md) |
| I1 | Shared tests as iOS code on the simulator | Done (2026-11-03): 194 tests, also in CI's iOS workflow | `iosSimulatorArm64Test` |
| I2 | Real Breez SDK on regtest | Done: 5 contract tests; found MOB-101 (fixed) | `BreezSdkContractTest`, `:tools:regtest:regtestContractTest` |
| I3 | Credential stores on a real Keystore/Keychain | Android done: 12 device tests, O-11 confirmed. iOS Keychain carried over (needs an app-hosted test target) | `AndroidPocketKeyStoreTest`, `AndroidCredentialVaultTest`, `SecureSettingsTest` |
| M12 | Test summary report | Done (2026-11-03) | [test-summary-report.md](test-summary-report.md) |
| SH1–SH6 | Should items | Done at host level (2026-11-02): SH1 (2.6), SH2 price degradation and backend response shape, SH4 hostile LNURL, SH5 fee mapping and receipt (MOB-94 fixed: an unreported fee shows as "unknown"), SH6 formatting per language. SH3 (exploratory charters) is stage 3. | `RaylBitcoinPriceProviderTest`, `RaylServerTest`, `KtorLnurlPayClientTest`, `LnurlInvoiceResolverTest`, `AmountFormatterTest` |

States: Not started · In progress · Blocked (reason) · Done.

## Stage 2 plan (2026-11-01)

Nicola has deferred decisions on requirements and defect fixes for now. Stage 2 therefore
pins the **current, documented behaviour**:
- Where the intended behaviour is open, the test is parked with a reason; it asserts
  neither option.
- A test that exposes a defect is not committed in a failing state. The defect is drafted
  for Linear and not fixed.

Slices, in order:

| # | Slice | Risk | Needs |
|---|---|---|---|
| 2.1 | **Done.** Settlement contract for the Rayl and Spark wallets: an uncertain or thrown outcome after submitting is `Unknown`, a `prepare` failure means not paid, cancellation propagates, fees are reported in msat (M3) | PR-04 | Nothing |
| 2.2 | **Done (2026-11-02).** The same contract for Blink and NWC (M3) | PR-04 | Done with test seams, see below |
| 2.3 | **Done.** Spark removal waits for the credential delete (M6, W2) | PR-08 | Nothing |
| 2.4 | **Done.** An unreadable pocket key still counts as a key (M7, K3) | PR-09 | Nothing |
| 2.5 | **Done.** LNURL `description_hash` match and mismatch, and amountless invoices (SH4, R1, R4) | PR-05 | Nothing |
| 2.6 | **Done.** Input corpus: uppercase BOLT11 and `LIGHTNING:`, untrimmed input, Lightning addresses, never-throws property test (SH1) | PR-14 | Nothing; the capitalised-address row is parked (O-08) |
| 2.7 | **Done.** Fiat quote: rounding to whole sats and invalid rates (M5, S2) | PR-01, PR-18 | Nothing |
| 2.8 | **Done.** Payment flow component tests: each source and outcome through `PaymentFlowCoordinator`, and the Auto Pay table through the flow (M2, M1); check O-02 (M4) | PR-01–PR-04 | Nothing; fee conditions parked (SI-01). O-02 can't be checked on the host (below). |

**Stop point:** after 2.8, or earlier if every remaining slice is blocked. The last step is a
short stage 2 summary (risk coverage, what's parked and why). Stage 3 (devices, manual and
static testing) starts only when Nicola says so.

**Parked:** fee-aware Auto Pay conditions (SI-01, not built), capitalised Lightning
addresses (O-08), the already-paid result per wallet (O-09). *All three were decided on
2026-11-02; see below.*

## Stage 2, continued (2026-11-02)

Nicola answered the open questions (test plan §15) and changed two rules:
- QA may change production code for testability without asking, explaining why in the commit.
- QA fixes defects as well. Every defect is filed in Linear, fixed with a regression test in
  its own commit, then closed.

| # | Slice | Risk | State |
|---|---|---|---|
| 2.9 | Record the decisions; file the telemetry issue and the defects from O-08, O-09, O-10 | | **Done:** MOB-89–MOB-92 |
| 2.10 | PIT as the mutation tool for the host suite; retire `mutate.sh` | | **Done** (`58ba3f68`) |
| 2.11 | Fix MOB-88 (threshold copy) | PR-02 | **Done** (`0af25e32`), closed |
| 2.12 | Fix O-10 (remove legacy fallbacks) | | **Done** (`5da46668`), MOB-91 closed |
| 2.13 | Fix O-08 (lowercase capitalised Lightning addresses) | PR-14 | **Done** (`43420709`), MOB-89 closed: LUD-16 requires lowercase, `2de24546` was wrong |
| 2.2 | Blink and NWC settlement contract, with test seams | PR-04 | **Done** (`ebc6cc6e`) |
| 2.14 | Fix O-09 (already paid is success in every wallet that can tell) | PR-15 | **Done** (`53aaf5c2`), MOB-90 closed. NWC unchanged (no reliable signal). Plus receipt and save-offer flow tests from PIT findings. |
| 2.15 | A property-testing library for random-input tests (D-07) | PR-14 | **Done:** kotest-property |
| — | O-05: code comment that a screen-protection strategy is still to be found | PR-11 | **Done** (`MainActivity`) |

## Level 1 risk coverage

The main progress indicator (master plan §11). "Today" is what existing tests cover.

| Risk | Today | Planned for 1.1 | Covered? |
|---|---|---|---|
| PR-01 Wrong amount | Amount entry, display and fiat quote rounding; the entered amount is paid exactly through the flow (2.7, 2.8). Real wallets and mainnet need stage 3. | M2, M5, M11 | Partly (host level done) |
| PR-02 Confirmation skipped | Policy decision table, and through the flow: threshold, Always mode, dismissal, manual entry, Hub preset (2.8). Fee limit not in 1.1 (MOB-93). | M1 | Yes |
| PR-03 Duplicate payment | Operation guard, paid-hash lookup and QR presence gate, also through the flow (2.8). After a restart, protection relies on each wallet (O-07, stage 3). | M4 | Partly (host level done) |
| PR-04 Outcome misreported | The Unknown-never-Failed rule and cancellation are protected for all four wallets (2.1, 2.2), and each wallet verdict's meaning is pinned. | M3, M2, M4 | Yes (host level) |
| PR-05 Wrong recipient | LNURL client and resolver, mutation-checked: `description_hash` binding, amountless invoices (2.5), DNS rebinding, impossible limits, missing invoice, nesting guard (SH4) | SH4 | Yes (host level) |
| PR-06 Fee surprises | Each wallet's fee mapping and the receipt's fee are tested, including a fee the wallet doesn't report, which shows as "unknown" (MOB-94, fixed). Fee limit is MOB-93, after 1.1. | SH5 | Partly (mainnet fees: M11) |
| PR-08 Credentials retained | Every SPEC-wallet-lifecycle rule traced (M6): fresh setup never reads old credentials (all three wallets), cleanup before switching, failed cleanup blocks and resumes. Platform wiring checked statically. Open: O-11, Keychain after reinstall (checklist). | M6, M10 | Yes at host level |
| PR-09 Pocket key lost | Pocket rules, passkey account selection, and when the key copy may be erased (`RaylWalletExitTest`), mutation-checked. External cleanup can't reach the pocket (static check). The platform stores have no tests (audit §9). | M7, M10 | Partly (host level done) |
| PR-10 Passkey onboarding | `RaylOnboardingStateTest`, `PocketSetupTest` (no-PRF path) | SH3, M10 | Partly |
| PR-11 Secrets exposed | Static review (M8): release logging, `toString()` redaction (MOB-95 fixed, tested), no secrets in the repository. Screen capture accepted (O-05). | M8 | Yes (static) |
| PR-13 Speed | `perfCheck` macrobenchmarks, not run regularly | One lab measurement (optional) | No |
| PR-14 Unparseable input | `PaymentInputCorpusTest`: one table of real-world input forms, plus a never-throws property test over 3,000 random inputs (2.6). Camera and real QR codes need stage 3. | S1, M10 | Partly (host level done) |
| PR-17 Interruptions | None | SH3, M10 | No |
| PR-19 Dependency change | `verifyBreezSdkPins` in the gate | M8 | Yes |
| PR-20 Release misconfigured | Static review (M8): minify, debuggable, backup, signing, debug tools. Version bump for the cut is a checklist item. | M8, M10 | Partly (M10 open) |
| PR-21 Legal documents | Reviewed (M9); privacy, terms and store listings rewritten for 1.1 and the in-app links fixed (MOB-96–MOB-98, MOB-102). MOB-99 open, not a blocker (Nicola, 2026-11-03). | M9, M10 (L2–L4) | Partly: Nicola's review of the wording, store console forms (L4) |
| PR-25 Update loses stored data (new) | Static diff against `release/1.0` (M8): compatible. No automated test yet. | U2/U3, host snapshot test | Partly |

## Open observations

Defects live in Linear only (team Mobile, label Bug); this table keeps just the observations that
are not issues. Open bugs: filter `label:Bug` and state not Done. On 2026-11-03: MOB-99 (not a release blocker).

| ID | Summary | Severity | State |
|---|---|---|---|
| O-02 | Spark may reset an in-flight payment session | Unknown | Parked for stage 3: the unguarded reset is called from Android Compose code (`SparkExperience.kt`), which host tests can't drive. Rayl and Blink guard it in common code. |
| O-07 | Duplicate detection after a restart relies on each wallet | Unknown | To check (M11) |
| O-11 | Android's secure store reports an undecryptable credential as absent, so Blink and NWC show setup instead of recovery | Unknown | Stage 3: check on a device whether that meets SPEC-wallet-lifecycle (see M6) |

## Stage 2 summary (2026-11-01)

Stage 2 stopped here as planned. Done: 2.1 and 2.3 to 2.8. Waiting: 2.2.

- **Suite:** 272 host tests in 59 classes, 194 of them also on the iOS simulator, 12 Android device tests, 5 regtest contract tests; all passing.
  Every new test was mutation-checked: each slice's target mutations are killed.
- **Closed gaps from the audit:** the Unknown-never-Failed rule for Rayl and Spark (S1, S2),
  Spark removal waiting for the credential delete (W2), the unreadable pocket key (K3),
  LNURL `description_hash` and amountless invoices (R1, R4), uppercase, untrimmed and
  Lightning-address input (L1–L5), fiat quote rounding and invalid rates (Q1, Q2), and the
  whole payment flow, which had no tests before.
- **Waiting for Nicola:**
  - 2.2, the settlement contract for Blink and NWC. `BlinkWallet` and `NwcWallet` are final
    classes with internal constructors, so the settlements can't be tested without a seam
    (an interface the settlement depends on). That is a small production change, not a
    requirement decision.
- **Parked until a decision:** fee-aware Auto Pay (SI-01), capitalised Lightning addresses
  (O-08), the already-paid result per wallet (O-09). *Decided 2026-11-02: O-08 and O-09
  fixed, SI-01 became MOB-93.*
- **Left for stage 3:** O-02 (Android UI code), the platform key and credential stores,
  the real Breez SDK, devices, and everything manual and static.
- **No new defects found.** One harmless finding: `bitcoin:…?lightning=%zz` is rejected as
  unrecognised rather than as a Bitcoin address (test log, 2.6).

