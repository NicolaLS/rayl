# 1.1 test status

Updated: 2026-11-01. Plan: [release-test-plan.md](release-test-plan.md).
Executions: [test-log.md](test-log.md).

## Work items

| # | Item | State | Artifacts |
|---|---|---|---|
| M1 | Auto Pay decision table | In progress: policy level done; through the flow next; fee conditions wait for SI-01 | `PaymentConfirmationPolicyTest` (6 tests) |
| M2 | Payment flow component tests | Not started | |
| M3 | Settlement classification, four wallets | Not started | |
| M4 | Duplicate payment, O-02 | Not started | |
| M5 | Conversion boundaries | Not started | |
| M6 | Wallet lifecycle traceability | Not started | |
| M7 | Pocket key rules | Not started | |
| M8 | Static reviews (secrets, release config, SDK pins) | Not started | |
| M9 | `distribution/` review | Not started | |
| M10 | Release checklist and App Live run | Not started | |
| M11 | Mainnet protocol and run | Not started | |
| M12 | Test summary report | Not started | |
| S1–S6 | Should items | Not started | |

States: Not started · In progress · Blocked (reason) · Done.

## Level 1 risk coverage

The main progress indicator (master plan §11). "Today" is what existing tests cover.

| Risk | Today | Planned for 1.1 | Covered? |
|---|---|---|---|
| PR-01 Wrong amount | `AmountConversionTest` (4), `ManualAmountControllerTest`, LNURL resolver | M2, M5, M11 | Partly |
| PR-02 Confirmation skipped | Policy decision table and msat boundary for every threshold step (`PaymentConfirmationPolicyTest`); nothing through the flow | M1 | Partly |
| PR-03 Duplicate payment | `PaymentOperationTest` (2), `PaymentHistoryTest` | M4 | Partly |
| PR-04 Outcome misreported | Per-wallet settlement tests (Rayl 5, Spark 6, Blink payment mapping); not run as one shared suite | M2, M3, M4 | Partly |
| PR-05 Wrong recipient | `LnurlInvoiceResolverTest` | S4 | Partly |
| PR-06 Fee surprises | None | M1 (once built), S5 | No |
| PR-08 Credentials retained | Spark lifecycle and removal, `ConnectionStorageResetTest`, `RaylSelectionTest` | M6, M10 | Partly |
| PR-09 Pocket key lost | `PocketSetupTest`, `RaylPocketTest` | M7, M10 | Partly |
| PR-10 Passkey onboarding | `RaylOnboardingStateTest`, `PocketSetupTest` (no-PRF path) | S3, M10 | Partly |
| PR-11 Secrets exposed | None | M8 | No |
| PR-13 Speed | `perfCheck` macrobenchmarks, not run regularly | One lab measurement (optional) | No |
| PR-14 Unparseable input | Parser tests (26 in `core/payment`) | S1, M10 | Partly |
| PR-17 Interruptions | None | S3, M10 | No |
| PR-19 Dependency change | None | M8 (SDK pins) | No |
| PR-20 Release misconfigured | None | M8, M10 | No |
| PR-21 Legal documents | None (O-04 known) | M9 | No |

## Open defects and observations

| ID | Summary | Severity | State |
|---|---|---|---|
| O-01 | Settings and onboarding copy say the threshold amount pays automatically; it asks for confirmation (decided: the code is right) | S4 | Drafted in [defect-drafts.md](defect-drafts.md), to file |
| O-02 | Spark may reset an in-flight payment session | Unknown | To check (M4) |
| O-04 | Terms and privacy policy don't mention the built-in wallet | S2 (release blocker by exit criterion 6) | To review (M9) |
| O-05 | No screen-capture protection for credentials on Android | Pending decision | Open (D-06) |
| O-07 | Duplicate detection after a restart relies on each wallet | Unknown | To check (M11) |
