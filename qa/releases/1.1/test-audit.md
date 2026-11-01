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
| 1 | `feature/payment-flow` | 8 | PR-02–PR-04 | Not audited | | | |
| 2 | `feature/payment-settings` | 6 + 3 classes | PR-02 | Policy test rewritten in `8b4855be`; the other 3 classes not audited | | | |
| 3 | `core/payment` | 26 | PR-05, PR-14 | Not audited | | | |
| 4 | `core/model` | 10 | PR-01 | Not audited | | | |
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
