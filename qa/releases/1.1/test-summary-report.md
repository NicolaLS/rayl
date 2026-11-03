# Test summary report: Rayl 1.1

Report date: 2026-11-03. Author: QA. Approver: Nicola Susca.
Test period: 2026-11-01 to 2026-11-03, on `main` (1.1 content; no release branch or release
candidate yet). Plans: [master test plan](../../test-plan.md),
[1.1 release test plan](release-test-plan.md). Executions: [test log](test-log.md).

## 1. Summary

**Automated testing below the UI is complete for 1.1. The release is not yet ready to ship**,
because the two activities that test the real app with real wallets (release checklist M10,
mainnet protocol M11) haven't run, and three S2 documentation defects are deferred.

- **Testware built:** a clean host suite (254 tests audited, 206 kept, now 272), the same suite on
  the iOS simulator (194), Android device tests on a real Keystore (12), contract tests against
  the real Breez SDK on regtest (5), mutation testing (PIT), static reviews, and the drafts of
  the release checklist and mainnet protocol.
- **Defects:** 11 found and filed in Linear (MOB-88 to MOB-91, MOB-94 to MOB-99, MOB-101).
  6 fixed and closed, 5 open: 4 deferred documentation defects and MOB-94 (S4, decided).
- **CI** was red since at least Oct 28 and is green again, now including the iOS test run.
- **Most valuable findings:** wrong assumptions about the real Breez SDK (MOB-101: unfunded or
  expired payments shown as "outcome unknown"), only found once the stubs were checked against
  the real SDK; and the terms not saying that the passkey is the only backup of Rayl's wallet
  (MOB-97).

## 2. What was tested

| Level | What | Result |
|---|---|---|
| Unit and component (host, stubs at the edges) | Payment flow, Auto Pay decision table, settlement contract of all four wallets, amount conversion, parser corpus and random input, LNURL including hostile servers, price backend failures, wallet lifecycle, pocket key rules, secret redaction | 272 tests, 0 failures, in the gate and CI |
| Same suite as iOS code | 24 modules on the iOS simulator (the 6 modules Xcode links to the Breez library run on the JVM only) | 194 tests, 0 failures, in CI's iOS workflow |
| Integration: real platform | Pocket key store, Spark recovery-phrase vault, Blink/NWC secure settings on a real Android Keystore (Android 36 emulator) | 12 tests, 0 failures; run manually (`connectedAndroidDeviceTest`), not in CI |
| Integration: real wallet SDK | Breez SDK 0.27.2 on Lightspark's hosted regtest: already paid, insufficient funds, expired, fee paid vs quoted, recovery from the same seed | 5 tests, 0 failures after MOB-101; run manually (`regtestContractTest`) |
| Test quality | Every test audited (stage 1); mutation testing with PIT on the payment, wallet and parser modules, plus hand-made mutants; several weak tests found and fixed this way | Documented in [test-audit.md](test-audit.md) and the commits |
| Static | Secrets in logs and `toString()`, release build configuration, Breez SDK pins (now a gate check), compatibility with what 1.0 stores, `distribution/` documents, wallet lifecycle spec traced rule by rule | [static-review.md](static-review.md), [distribution-review.md](distribution-review.md), [lifecycle-traceability.md](lifecycle-traceability.md) |

**Not tested:**
- **System level:** the release checklist on App Live (M10, Nicola). It covers real devices,
  passkeys, camera, interruptions, languages and the update from 1.0.
- **Acceptance level:** the mainnet protocol with real money (M11, Nicola).
- Exploratory sessions (SH3).
- iOS Keychain stores: need an app-hosted test target.
- Blink and NWC against their real services.
- UI automation: deferred, D-03.
- Performance: no baseline yet.

## 3. Defects

| Issue | Summary | Sev. | State |
|---|---|---|---|
| MOB-88 | Threshold copy said the threshold amount pays automatically | S4 | Fixed |
| MOB-89 | Capitalised Lightning address rejected | S3 | Fixed (lowercase per LUD-16) |
| MOB-90 | Already-paid invoice shown as an error on Rayl wallet and Spark | S3 | Fixed (NWC can't tell) |
| MOB-91 | Legacy Spark onboarding default | S4 | Fixed |
| MOB-94 | Fee shown as 0 when Blink/NWC don't report it | S4 | **Open**: show "unknown" (decided), next fix batch |
| MOB-95 | Credentials printed by `toString()` | S3 | Fixed |
| MOB-96 | Privacy policy describes 1.0 | S2 | **Open, deferred** |
| MOB-97 | Terms miss Rayl's wallet, above all "the passkey is the only backup" | S2 | **Open, deferred** |
| MOB-98 | Store listings outdated or placeholders | S3 | **Open, deferred** |
| MOB-99 | iOS encryption export assessment not redone for the Breez SDK | S2 | **Open, deferred** |
| MOB-101 | Insufficient funds and expired invoices shown as "outcome unknown" | S3 | Fixed |

Improvements and features filed along the way: MOB-92 (privacy-aligned telemetry), MOB-93 (fee
limit setting), MOB-100 (a durable nostr-kmp dependency instead of disappearing snapshots).

Defect clustering: the wallet SDK boundary (MOB-90, MOB-101: how Breez reports refusals) and the
documents (MOB-96 to MOB-99). The payment logic itself produced no defect once its tests were
strengthened.

## 4. Exit criteria (master plan §9, as adapted for 1.1)

| # | Criterion | State |
|---|---|---|
| 1 | No open S1/S2 defects; open S3 accepted | **Not met:** MOB-96, MOB-97, MOB-99 (S2) and MOB-98 (S3) are open and deferred. Needs a go/no-go decision |
| 2' | Must items M1–M8 and I1–I3 done and passing; residual risk accepted here | **Met**, except iOS Keychain (part of I3); residual risk in §5 |
| 3 | Release checklist passes on App Live | **Not run** (Nicola) |
| 4 | Mainnet protocol passes for every shipped wallet | **Not run** (Nicola) |
| 5 | No performance regression | **Not applicable** for 1.1 (no baseline) |
| 6 | `distribution/` matches the release | **Not met**, deferred (same issues as criterion 1) |
| 7 | This report written and approved | Written; awaiting approval |

**QA recommendation:** go only after M10 and M11 have passed and the S2 documentation defects
are either fixed or explicitly accepted. MOB-97 and MOB-99 are the two I wouldn't accept: one
leaves users uninformed about how they can lose funds, the other is an export-compliance answer
for the iOS submission.

## 5. Residual risk (Level 1 risks)

| Risk | Covered by | What remains |
|---|---|---|
| PR-01 Wrong amount | Host flow and conversion tests; iOS formatter | Real wallets confirm the amount on mainnet (M11) |
| PR-02 Confirmation skipped | Decision table and flow tests | Low; screens not automated (M10) |
| PR-03 Duplicate payment | Guard and paid-hash lookup; already-paid verified on the real SDK | After a restart, protection depends on each wallet (O-07); Blink/NWC unverified for real (M11) |
| PR-04 Outcome misreported | Settlement contract for all four wallets; real SDK refusals (MOB-101 fixed) | Blink and NWC outcomes only against stubs; network-failure behaviour of the real SDK not provoked |
| PR-05 Wrong recipient | LNURL client and resolver incl. hostile servers | Real LNURL services (M11) |
| PR-06 Fee surprises | Fee mapping and receipt; quoted = paid on regtest (Lightning route) | MOB-94 open; no fee limit (MOB-93, after 1.1); mainnet fees (M11) |
| PR-08 Credentials retained | Lifecycle rules traced; Android stores on a real Keystore | iOS Keychain stores untested; O-11 (unreadable credential shows setup, not recovery) to judge on device |
| PR-09 Pocket funds inaccessible | Key-copy rules in common code and on a real Android Keystore; same seed finds same balance (real SDK) | iOS Keychain; real passkey providers (M10); the terms don't warn users (MOB-97) |
| PR-10 Passkey onboarding | Host tests with fake passkeys | Real password managers and PRF support only on devices (M10) |
| PR-11 Secrets exposed | Static review; `toString()` redaction tested | No screen-capture protection (O-05, accepted) |
| PR-13 Speed | None | No baseline (future goal) |
| PR-14 Unparseable input | Corpus and random input, JVM and iOS | Real QR codes and camera (M10) |
| PR-17 Interruptions | Cancellation tests in the flow and settlements | App backgrounded or killed mid-payment, and O-02 (Spark reset), only on devices (M10) |
| PR-19 Dependency change | Breez pin check in the gate; contract tests catch SDK behaviour changes | Contract tests are manual; the nostr-kmp snapshot can disappear again (MOB-100) |
| PR-20 Release misconfigured | Static review | Version bump for the cut (checklist B1); release-branch gating (B2) |
| PR-21 Legal documents | Reviewed | MOB-96 to MOB-99 open |
| PR-25 Update loses stored data | Static comparison with `release/1.0` | No automated test yet; real update in M10 (U2, U3) |

## 6. Carried over to the next test project

In priority order:
1. **Run M10 and M11 for 1.1** (Nicola), then add their results to this report. They also verify
   the remaining stub assumptions (`qa/stub-assumptions.md`), e.g. Blink and NWC behaviour, and
   how NWC wallets word "already paid" (MOB-90).
2. **Fix batch:** MOB-94 ("unknown" fee); the deferred documents MOB-96 to MOB-99.
3. **PR-25 host test:** open a 1.0-shaped settings snapshot in the current code (connection,
   Hub, Auto Pay threshold survive). Needed before every release from now on.
4. **iOS Keychain store tests** in an app-hosted XCTest target (pocket key copy, Spark vault,
   secure settings), the iOS half of I3.
5. **Automate what now runs manually:** Android device tests in CI (emulator job), the regtest
   contract tests on a schedule (needs a funded bank in CI), and MOB-100 so CI stays green.
6. **Exploratory charters** (SH3): interruptions and passkey environments.
7. **Remaining host-level gaps found by PIT** (e.g. haptics and Hub "last paid" ordering after a
   payment): low risk, picked up when those areas change.
8. **Later goals from the master plan:** a performance baseline and then regression tolerance,
   UI automation (D-03), telemetry-based field data (MOB-92), and a decision on O-11.

## 7. Notes for the next project

- **Check the stubs against reality early.** The host tests were thorough but encoded two wrong
  assumptions about the Breez SDK; the first run against the real SDK found MOB-101.
  `qa/stub-assumptions.md` lists what is still unchecked.
- **Local builds can hide CI failures.** `mavenLocal()` hid the missing nostr-kmp snapshot, and
  the configuration cache switched off in `~/.gradle/gradle.properties` hid three failing tasks.
- **Mutation testing earns its time.** PIT found tests that passed for the wrong reason (a
  coincidental $5.00, a cache test starting at clock 0, a nesting test that never reached the
  guard), which reviews had missed.
- **Defect policy in practice** (test plan §10): S1/S2 fixed at once, S3/S4 known-defect tests and
  batches, `main` never red.
