# 1.1 test log

Each test execution for 1.1, newest first: what ran, on which build, and the result.

## 2026-11-01: M1 policy decision table

- **Build:** `main` at `c3c00e81` plus the rewritten `PaymentConfirmationPolicyTest`
  (uncommitted)
- **Ran:** `./gradlew :feature:payment-settings:testAndroidHostTest :feature:payment-settings:ktlintCheck`
- **Result:** 6 tests, 0 failures. They replace the 3 earlier policy tests, which are rows of
  the new table. Module total: 9 tests.
- **Test strength check:** changing the policy's `>=` to `>` (confirmation strictly above the
  threshold) made 3 of the 9 module tests fail. The change was reverted.
- **Findings:**
  - O-01 confirmed and widened: the settings copy *and* the onboarding label ("Auto-pay up
    to 10,000 sats") say the threshold amount pays automatically, in en/de/es on both
    platforms. Drafted as an S4 defect in [defect-drafts.md](defect-drafts.md).
  - The preferences load synchronously when the repository is created, so "preferences not
    loaded yet at cold start" (decision table condition in master plan §6.2) can't happen
    at the policy. It stays a condition for the flow-level tests if loading becomes
    asynchronous.
  - A corrupted stored threshold is clamped to 100,000 sats on load, so it can't disable
    confirmation for large payments.

## 2026-11-01: Baseline of the automated suite

- **Build:** `main` at `c3c00e81` (macOS host, local Gradle; CI is manual-only, PJ-01)
- **Ran:** `./gradlew ktlintCheck verifyModuleDependencies verifyNativeLocalizations testAndroidHostTest :backend:rayl:test`,
  then `cleanTestAndroidHostTest testAndroidHostTest` to force the host tests to run again
  (some results came from the Gradle build cache, which is valid for identical inputs).
- **Result:** all checks passed. **254 tests in 63 classes across 32 modules: 0 failures,
  0 skipped, 26 s of test time.**
- **Count correction:** the master plan's earlier figure of 275 included 18 stale results
  from the archived Club module (`RaylClubTest`, left behind in `build/`). Commit
  `185aa4e0` (deep links removed) then removed 3 tests. Count only result files written by
  the current run.

Largest modules by test count: `providers/rayl/integration/pocket` (31),
`core/payment` (26), `providers/spark/application` (26), `providers/blink/integration/blink`
(16), `feature/payment-ui` (15), `providers/rayl/experience` (15). `feature/payment-flow`
has 8, none of them on `PaymentFlowCoordinator` or the admission and confirmation phases.
