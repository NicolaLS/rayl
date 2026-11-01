# 1.1 test status

Updated: 2026-11-01. Plan: [release-test-plan.md](release-test-plan.md).
Executions: [test-log.md](test-log.md).

## Work items

Current focus: stages 1 and 2, host-level tests only ([plan §3.1](release-test-plan.md#31-current-focus-host-level-tests-only-decided-2026-11-01)).

| # | Item | State | Artifacts |
|---|---|---|---|
| A | Stage 1: audit the existing tests | **Done** (254 → 206 tests, after the Linear cross-check) | [test-audit.md](test-audit.md) |
| B | Stage 2: minimal host-level suite | In progress, see the stage 2 plan below | |
| M1 | Auto Pay decision table | In progress: policy level done; through the flow next; fee conditions wait for SI-01 | `PaymentConfirmationPolicyTest` (6 tests) |
| M2 | Payment flow component tests | Not started | |
| M3 | Settlement classification, four wallets | In progress: Rayl and Spark done (2.1); Blink and NWC need a test seam (2.2) | `RaylPaymentSettlementTest`, `SparkPaymentSettlementTest` |
| M4 | Duplicate payment, O-02 | Not started | |
| M5 | Conversion boundaries | Not started | |
| M6 | Wallet lifecycle traceability | Not started | |
| M7 | Pocket key rules | Host-level part done (2.4); the platform stores need stage 3 | `RaylPocketTest` |
| M8 | Static reviews (secrets, release config, SDK pins) | Not started | |
| M9 | `distribution/` review | Deferred to stage 3 | |
| M10 | Release checklist and App Live run | Not started | |
| M11 | Mainnet protocol and run | Not started | |
| M12 | Test summary report | Not started | |
| S1–S6 | Should items | Not started | |

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
| 2.2 | The same contract for Blink and NWC (M3) | PR-04 | **A test seam** (an interface the settlement depends on) in both settlements: a small production change, waiting for Nicola's OK |
| 2.3 | **Done.** Spark removal waits for the credential delete (M6, W2) | PR-08 | Nothing |
| 2.4 | **Done.** An unreadable pocket key still counts as a key (M7, K3) | PR-09 | Nothing |
| 2.5 | LNURL `description_hash` match and mismatch, and amountless invoices (S4, R1, R4) | PR-05 | Nothing |
| 2.6 | Input corpus: uppercase BOLT11 and `LIGHTNING:`, untrimmed input, Lightning addresses, never-throws property test (S1) | PR-14 | Nothing; the capitalised-address row is parked (O-08) |
| 2.7 | Fiat quote: rounding to whole sats and invalid rates (M5, S2) | PR-01, PR-18 | Nothing |
| 2.8 | Payment flow component tests: each source and outcome through `PaymentFlowCoordinator`, and the Auto Pay table through the flow (M2, M1); check O-02 (M4) | PR-01–PR-04 | Nothing; fee conditions parked (SI-01) |

**Stop point:** after 2.8, or earlier if every remaining slice is blocked. The last step is a
short stage 2 summary (risk coverage, what's parked and why). Stage 3 (devices, manual and
static testing) starts only when Nicola says so.

**Parked:** fee-aware Auto Pay conditions (SI-01, not built), capitalised Lightning
addresses (O-08), the already-paid result per wallet (O-09).

## Level 1 risk coverage

The main progress indicator (master plan §11). "Today" is what existing tests cover.

| Risk | Today | Planned for 1.1 | Covered? |
|---|---|---|---|
| PR-01 Wrong amount | `AmountConversionTest` (4), `ManualAmountControllerTest`, LNURL resolver | M2, M5, M11 | Partly |
| PR-02 Confirmation skipped | Policy decision table and msat boundary for every threshold step (`PaymentConfirmationPolicyTest`); nothing through the flow | M1 | Partly |
| PR-03 Duplicate payment | Operation guard and paid-hash lookup, mutation-checked (`PaymentOperationTest`, `PaymentHistoryTest`) | M4 | Partly |
| PR-04 Outcome misreported | The Unknown-never-Failed rule and cancellation are protected for Rayl and Spark (2.1). Blink's payment client is well covered, but its settlement and NWC's have no tests (2.2). | M3, M2, M4 | Partly |
| PR-05 Wrong recipient | LNURL client and resolver, mutation-checked; `description_hash` check untested (audit §3); the code checks it | S4 | Partly |
| PR-06 Fee surprises | None | M1 (once built), S5 | No |
| PR-08 Credentials retained | Spark lifecycle and removal, mutation-checked. Removal waiting for the credential delete is now tested (2.3). | M6, M10 | Partly |
| PR-09 Pocket key lost | Pocket rules and passkey account selection, mutation-checked. The never-replace rule lives only in the platform stores, which have no tests (audit §9). | M7, M10 | Partly |
| PR-10 Passkey onboarding | `RaylOnboardingStateTest`, `PocketSetupTest` (no-PRF path) | S3, M10 | Partly |
| PR-11 Secrets exposed | None | M8 | No |
| PR-13 Speed | `perfCheck` macrobenchmarks, not run regularly | One lab measurement (optional) | No |
| PR-14 Unparseable input | Parser and LNURL parsing tests; uppercase, untrimmed and Lightning-address input untested (audit §3) | S1, M10 | Partly |
| PR-17 Interruptions | None | S3, M10 | No |
| PR-19 Dependency change | None | M8 (SDK pins) | No |
| PR-20 Release misconfigured | None | M8, M10 | No |
| PR-21 Legal documents | None (O-04 known) | M9 | No |

## Open defects and observations

| ID | Summary | Severity | State |
|---|---|---|---|
| O-01 | Settings and onboarding copy say the threshold amount pays automatically; it asks for confirmation (decided: the code is right) | S4 | Filed: [MOB-88](https://linear.app/nicola-susca/issue/MOB-88) |
| O-02 | Spark may reset an in-flight payment session | Unknown | To check (M4) |
| O-04 | Terms and privacy policy don't mention the built-in wallet | S2 (release blocker by exit criterion 6) | To review (M9) |
| O-05 | No screen-capture protection for credentials on Android | Pending decision | Open (D-06) |
| O-07 | Duplicate detection after a restart relies on each wallet | Unknown | To check (M11) |
| O-09 | Rescanning an already-paid invoice: Blink shows success (already paid), Rayl and Spark show an error | S3 (parity) | Product decision needed |
| O-10 | Legacy default in `SparkOnboardingState` (restored wallets skip the intro screens) | S4 | For Nicola: remove under the pre-release policy |
| O-08 | `Alice@example.com` (capitalised Lightning address) is rejected as unrecognised | S3 if the decision is to accept it | Product decision needed |
