# 1.1 test log

Each test execution for 1.1, newest first: what ran, on which build, and the result.

## 2026-11-01: Audit of wallet selection and storage

- **Build:** `main` at `f88de8c6` plus the audit changes (uncommitted)
- **Ran:** 3 mutation checks, then the tests and ktlint of `app/shared` and the
  `core/settings` tests.
- **Result:** 2 killed, 1 equivalent. Both modules pass.
- Findings: [test-audit.md §11](test-audit.md#11-wallet-selection-and-storage-appshared-coresettings-2026-11-01).

## 2026-11-01: Audit of the Spark wallet modules

- **Build:** `main` at `f26b25b7` plus the audit changes (uncommitted)
- **Ran:** 3 mutation checks, then the tests and ktlint of the four Spark modules.
- **Result:** 2 killed, 1 survived (W2, a Critical PR-08 gap). The modules pass.
- **Incident:** the first removal of `concurrentCloseCallsShareOneSuccessfulCleanup` also
  cut the fixtures declared after the test class. The compile failure showed it; the file
  was restored and the tests were removed by brace matching instead.
- Findings: [test-audit.md §10](test-audit.md#10-spark-wallet-2026-11-01).

## 2026-11-01: Audit of the pocket

- **Build:** `main` at `38757831` plus the audit changes (uncommitted)
- **Ran:** 5 mutation checks, then the tests and ktlint of
  `providers/rayl/integration/pocket` and `providers/rayl/experience`.
- **Result:** 3 killed, 1 equivalent, 1 survived (K3, a stage 2 gap). Both modules pass
  (38 tests).
- Findings: [test-audit.md §9](test-audit.md#9-pocket-providersraylintegrationpocket-providersraylexperience-2026-11-01).

## 2026-11-01: Audit of settlement in each wallet

- **Build:** `main` at `84e54e3c` plus the audit changes (uncommitted)
- **Ran:** 8 mutation checks across the Rayl, Spark, NWC and Blink payment modules, then
  the tests and ktlint of the changed modules.
- **Result:** all 5 mutations of the Unknown-never-Failed rule survived (the headline
  finding); the other 3 were killed. The changed modules pass.
- **Tool fix:** `mutate.sh` now finds results through the task path (see the audit §8).
- Findings and observation O-09: [test-audit.md §8](test-audit.md#8-settlement-in-each-wallet-2026-11-01).

## 2026-11-01: Audit of `integration/lnurl`

- **Build:** `main` at `7f972deb` plus the audit changes (uncommitted)
- **Ran:** 5 mutation checks before and 4 after the changes, then the module's tests and
  ktlint.
- **Result:** all meaningful mutations killed; N3 is an equivalent mutant. The module passes
  with 7 tests.
- Findings: [test-audit.md §7](test-audit.md#7-integrationlnurl-2026-11-01).

## 2026-11-01: Audit of the price modules

- **Build:** `main` at `09267645` plus the audit changes (uncommitted)
- **Ran:** 4 mutation checks, then the tests and ktlint of `feature/payment-currency` and
  `integration/exchange-rate`, and the `backend/rayl` tests.
- **Result:** after the improvement, 2 killed and 2 survived (both stage 2 gaps). All three
  modules pass (17 tests).
- Findings: [test-audit.md §6](test-audit.md#6-prices-featurepayment-currency-integrationexchange-rate-backendrayl-2026-11-01).

## 2026-11-01: Audit of `feature/payment-ui`

- **Build:** `main` at `fd127430` plus the audit change (uncommitted)
- **Ran:** 7 mutation checks after deleting 5 tests, then the module's tests and ktlint.
- **Result:** all 7 killed, so the deletions removed no protection. The module passes with
  10 tests.
- Findings: [test-audit.md §5](test-audit.md#5-featurepayment-ui-2026-11-01).

## 2026-11-01: Audit of `core/model`

- **Build:** `main` at `ccd4a102` plus the audit change (uncommitted)
- **Ran:** 2 mutation checks, then the module's tests and ktlint.
- **Result:** 1 killed, 1 survived (display rounding, minor). The module passes with 8 tests.
- Findings: [test-audit.md §4](test-audit.md#4-coremodel-2026-11-01).

## 2026-11-01: Audit of `core/payment`

- **Build:** `main` at `65cad36c` plus the audit change (uncommitted)
- **Ran:** 9 mutation checks against `:core:payment:testAndroidHostTest`, then the module's
  tests and ktlint.
- **Result:** 2 killed, 7 survived (6 of them significant; all become stage 2 gaps). The
  module passes with 25 tests.
- Findings and observation O-08: [test-audit.md §3](test-audit.md#3-corepayment-2026-11-01).

## 2026-11-01: Audit of `feature/payment-settings`; pilot revised

- **Build:** `main` at `2f7d2d23` plus the audit changes (uncommitted)
- **Ran:** 3 mutation checks against `:feature:payment-settings:testAndroidHostTest`, then
  the tests and ktlint of `feature/payment-settings` and `feature/payment-flow`.
- **Result:** all 3 mutations killed; both modules pass (8 and 9 tests).
- Findings: [test-audit.md §2](test-audit.md#2-featurepayment-settings-2026-11-01).

## 2026-11-01: Audit of `feature/payment-flow` (pilot)

- **Build:** `main` at `8b4855be` plus the improved `PaymentHistoryTest` (uncommitted)
- **Ran:** 7 mutation checks with `qa/tools/mutate.sh` against
  `:feature:payment-flow:testAndroidHostTest`, then the module's tests and ktlint.
- **Result:** 6 mutations killed, 1 survived (`reset` keeping stored receipts). After the
  improvement all 7 are killed. The module has 10 tests (was 8), 0 failures.
- **Tool note:** the first version of `mutate.sh` misreported results (it couldn't parse
  multi-line JUnit XML or count multi-line matches). It was fixed before any result was
  recorded.
- Findings: [test-audit.md §1](test-audit.md#1-featurepayment-flow-2026-11-01-pilot).

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
