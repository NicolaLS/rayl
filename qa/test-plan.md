# Rayl master test plan

Status: **draft v0.1 for review**, 2026-11-01. Owner: QA. Approver: Nicola Susca (CEO/CTO).

This is the first test plan for Rayl. 1.0 is in store review and 1.1 follows right after
it, so this plan targets `main` and the **1.1 release**. 1.0 gets only a release-build smoke
test (D-08). It loosely follows ISO/IEC/IEEE 29119-3 and the ISTQB test process,
adapted for a one-person product team moving fast. It is a living document: update it
when scope, risks or decisions change, and record decisions in §15.

Related artifacts:
- [Product risk register](product-risks.md): what could go wrong, ranked.
- [QA artifacts index](README.md).

---

## 1. Context

**Product.** Rayl is a Lightning app for *paying*, not a general wallet. It should feel
like a debit card or Apple Pay: you open it, scan, and it pays. There are two ways to pay:
- **Rayl's built-in wallet (the "pocket").** An app-owned Spark wallet through the Breez
  SDK, whose key is derived from a passkey. This is the default.
- **One connected external wallet:** Blink (API key), NWC (connection URI), or Spark
  (recovery phrase). It is connected from Settings → Use another wallet.

All four share `feature/payment-flow`, which handles input admission, LNURL, confirmation
or Auto Pay, session history and Hub reporting. Each provider owns funding and
settlement. `backend/rayl` only serves aggregated BTC fiat prices.

**Platforms.** Android (minSdk 24, targetSdk 36, Compose UI) and iOS (18.5+, SwiftUI UI).
State and logic live in common Kotlin; only the hero animation is shared Compose.

**Quality goals**, in the stakeholder's order (interview, 2026-11-01):
1. **Money correctness:** right amount, right recipient, confirmed when it should be,
   never paid twice, outcomes reported honestly.
2. **Wallet and credential safety:** the [wallet lifecycle](../specs/SPEC-wallet-lifecycle.md)
   holds, the pocket key is never lost, secrets never leak.
3. **Speed of the staple path:** cold start → Scan → paid. Faster is better, and it must
   never regress.
4. **One experience** across providers ([REQ-provider-experience](../specs/REQ-provider-experience.md))
   and across platforms, with deliberately simple UX: no unnecessary dialogs or steps.

**Constraints**
- One person is founder, developer and release manager. QA is an AI agent working in
  sessions; continuity lives in this directory.
- QA does not run UI/E2E or on-device tests unless asked.
- The decision on UI automation tooling is deferred (D-03).
- Pre-release: no data migrations or legacy fallbacks to test.
- `main` moves fast; release branches remove providers that aren't ready.
- The first store review is slow, so `main` is already well past the 1.0 branch. Test
  effort goes to `main` and 1.1, not the frozen 1.0 branch.

## 2. Test basis

- Linked Specs: [ARCH-rayl](../specs/ARCH-rayl.md), [SPEC-wallet-lifecycle](../specs/SPEC-wallet-lifecycle.md),
  [REQ-provider-experience](../specs/REQ-provider-experience.md),
  [ARCH-providers](../providers/specs/ARCH-providers.md),
  [ARCH-payment-hub](../feature/payment-hub/specs/ARCH-payment-hub.md).
- The [terms](../distribution/terms.md), [privacy policy](../distribution/privacy.md) and
  store listings (en/de/es) under `distribution/`. These are promises to users and are
  testable.
- KDoc contracts in code, e.g. `PaymentSettlement`, `PocketKeyStore`, `PaymentOperation`.
- Stakeholder decisions recorded in §15.
- Linear issues (team `MOB`): acceptance criteria of done issues, and the defects behind
  `fix:` commits. Tests that verify them are part of the regression suite.
- Lightning and LNURL standards (BOLT11, LUD-01/06/09/16/17, BIP21, NIP-47), which act as
  external interoperability requirements.

Gaps in the test basis are reported to the stakeholder, not filled in by guessing.
Example: the intended Auto Pay boundary (O-01).

## 3. Scope

### In scope (test items)

| Area | Main modules |
|---|---|
| Payment input: QR, paste; BOLT11, LNURL-pay, Lightning address | `core/payment`, `core/camera`, `integration/lnurl` |
| Shared payment flow: admission, amount entry, confirmation or Auto Pay, outcome, session history | `feature/payment-flow`, `feature/payment-ui`, `feature/payment-currency`, `feature/payment-settings` |
| Rayl pocket: passkey onboarding, key storage, connect/sync, quote/pay/outcome, add funds, balance | `providers/rayl/**` |
| External wallets: setup, restore, payment, removal | `providers/blink/**`, `providers/nwc/**`, `providers/spark/**` |
| Wallet selection and lifecycle | `app/shared` (`RaylSelection`), provider storage cleanup, `core/settings` |
| Hub: contacts, presets, payment hand-off | `feature/payment-hub` |
| Settings: confirmation mode and threshold, currency, language, theme, haptics | `feature/*-settings` |
| Fiat prices | `backend/rayl`, `core/rayl-api`, `integration/exchange-rate` |
| App shell, onboarding, Android and iOS hosts, release build configuration | `feature/app-shell`, `feature/onboarding`, `app/*` |
| User-facing documents | `distribution/` |

### Out of scope

- **Internals of third-party systems:** the Breez SDK, Spark operators, the Blink API, NWC
  wallets and relays, LNURL servers, the Lightning network. We test *our integration* and
  how we classify their behaviour, not the systems themselves.
- **Features the product deliberately excludes:** on-chain sends, BOLT12, receiving beyond
  "add funds", and general-wallet features. These are tested only to check they are
  rejected cleanly (e.g. the "BOLT12 not supported" message).
- Archived work (Club, Hub prototypes).
- `tools/regtest` as a product. It is a test tool.

### 3.1 Planned product changes that shape scope

While a change is undecided, the affected area gets **no new test investment**. Existing
tests stay, and whatever ships still gets the release checklist. When a change is decided,
update this table and the risk register.

| Change | Status | Effect on testing |
|---|---|---|
| **Remove all deep links** (payment and wallet connection). Rayl is built for in-person checkout and Hub payments, not to be a general wallet's link handler. Add funds still hands its invoice to another wallet as a `lightning:` link. | Done (2026-11-01) | Nothing to test for incoming links. Add funds: the handoff, and the invoice sheet when no wallet app is installed. Automated runs need an E2E-only way to inject scanned input (§12, after 1.1); the regtest tool's `--open` is gone. |
| Replace the custom payment-input parser with the Breez SDK parser | Not pursued (2026-11-01, D-10) | The custom parser stays: corpus and property tests are in scope (P1.9, PR-14). |
| Get fiat rates from the Breez SDK instead of `backend/rayl` | Not pursued (2026-11-01, D-10) | The backend stays: contract and degradation tests are in scope (P1.8, PR-18). |
| **Fee-aware Auto Pay** (SI-01 decision) | Decided, to be built for 1.1 | New decision-table conditions (P1.2) and fee checks (P1.7). Blocked until implemented. |

## 4. Risk-based approach

Effort follows the [risk register](product-risks.md). Level 1 risks get systematic
techniques at two or more levels, automated regression on every change, and an explicit
release exit criterion. Level 2 risks get one automated level plus a checklist item.
Level 3 risks get exploratory coverage.

The Level 1 focus for 1.1, grouped by the stakeholder's priorities:
- **Money:** PR-01 to PR-06
- **Wallet and credential safety:** PR-08 to PR-11
- **Plus:** PR-13 (speed), PR-14 (scanning real-world input), PR-17 (interruptions),
  PR-19 (dependency changes), PR-20 (release configuration), PR-21 (legal docs)

One of these is held back for now: **PR-06 (fees under Auto Pay)**. SI-01 is decided
(appendix B), but the fee-aware Auto Pay isn't built yet, so its tests wait for it.

## 5. Baseline assessment (2026-11-01)

What exists, measured before this plan:

- **257 JVM unit tests** in 63 classes, all passing, about 26 s of test time
  (`testAndroidHostTest` plus `:backend:rayl:test`). An earlier count of 275 included 18
  stale results from the archived Club module; see the
  [1.1 test log](releases/1.1/test-log.md). That is roughly 5.9k test LOC against
  roughly 38k Kotlin and 6.5k Swift LOC.
- **Well covered:** input parsers, the LNURL resolver and client, Spark wallet lifecycle and
  removal, pocket setup and pocket state, Blink payment mapping, `RaylSelection`, Hub
  repository and view model, backend price aggregation.
- **Largest gaps, by risk:**
  - **The core payment flow has no tests.** `PaymentFlowCoordinator`,
    `PaymentAdmissionPhase` and `PaymentConfirmationPhase` (about 900 LOC) are where Auto
    Pay, amounts and outcome handling come together. The policy has a unit test, but its
    use in the flow doesn't. (PR-01 to PR-04)
  - **Platform credential stores are not tested on a device.** This includes the Android
    Keystore-backed `PocketKeyStore` and the encrypted stores. One iOS Keychain test exists,
    but no workflow runs `iosTest`. (PR-08, PR-09)
  - **Several modules have zero tests:** `feature/onboarding`, `feature/settings`, the
    wallet-connection and onboarding features of Blink and NWC, `feature/wallet-management`,
    `core/network`, and all Swift code. (PR-10, PR-15, PR-16)
  - **No test fakes are shared.** For example, `TestSecureStringStore` is duplicated in
    Blink and NWC. Each module writes its own fakes, which slows down new tests.
- **CI is manual-only** (PJ-01). When it runs, it does ktlint, the module-dependency and
  localisation checks, host unit tests, the backend tests, and the Android and iOS builds.
- **Coverage:** Kover is applied but nothing reports on it or sets a threshold.
- **Performance:** there are Android macrobenchmarks for cold start and camera start
  (`perfCheck`), and debug-only `RaylTiming` marks along the pocket payment path. Nothing
  measures the full staple path, and there is no field data.
- **Manual QA:** the regtest bank (`tools/regtest`) supports manual pocket funding and
  payments on hosted Regtest.

## 6. Test strategy

### 6.1 Test levels

| Level | What | Where and tooling | Cadence |
|---|---|---|---|
| **Unit** | Pure logic: parsers, conversion, policies, state reducers, error mapping | `commonTest`, kotlin-test, coroutines-test, run on the JVM host | Every change |
| **Component** (the main investment) | One feature driven through its public API with fakes at the edges. Examples: `PaymentFlowCoordinator` with a fake settlement, a fake LNURL server (Ktor `MockEngine`), fake prices and a test clock; a runtime with a fake wallet | `commonTest` | Every change |
| **Contract** | Shared suites every implementation must pass, e.g. one `PaymentSettlement` suite run against the Rayl, Blink, NWC and Spark settlements (each with its SDK or API faked), and one `PocketKeyStore` suite per platform. The client/backend wire contract is pinned with golden JSON. | `commonTest`, plus platform source sets for stores | Every change |
| **Platform integration** | Real platform services: Android Keystore and encrypted preferences, iOS Keychain, the camera pipeline | Android instrumented tests on an emulator; `iosSimulatorArm64Test` | Nightly, and on changes to `core/settings`, `integration/pocket` or `core/camera` |
| **SDK integration** | The real Breez SDK on the JVM against Lightspark hosted Regtest, using the regtest bank as counterparty: connect, quote, pay, already-paid, insufficient funds, and classification of real SDK errors | New JVM test task next to `tools/regtest`; network-dependent, so it never gates a PR | Nightly, and before every release candidate |
| **System** | The complete app on devices: scripted regression checklist plus exploratory sessions | Manual on the device matrix (§7). UI automation deferred (D-03) | Each release candidate; exploratory each milestone |
| **Acceptance** | Real money on mainnet, all shipped providers, both platforms, small fixed budget | Manual, following the mainnet protocol (§7) | Each release candidate |

Logic should move down into common Kotlin whenever possible: it is the cheapest place to
test it, and both platforms benefit. Native UI layers should stay thin renderers of shared
state. Under that rule, a missing Swift test is an acceptable gap; untested logic in Swift
isn't.

### 6.2 Focus suites (designed in phases, §12)

1. **Money-path suite** (PR-01 to PR-06). Component tests of the coordinator:
   - input → admission → confirmation or Auto Pay → settlement → outcome, history and Hub
     report
   - for each payment source (camera, Hub preset, Hub address, manual)
   - for each outcome (Paid, Failed, Unknown, thrown, cancelled)
   - plus the duplicate-input and in-flight cases
2. **Auto Pay decision table** (PR-02, PR-06). Conditions: source × manual entry × preset
   target × confirmation mode × amount relative to the threshold (below, equal, above) ×
   fiat or sats × preferences loaded or not. The table is tested on the policy *and*
   end-to-end through the coordinator. Fee conditions are added once SI-01 is specified.
3. **Settlement contract suite** (PR-04, PR-07, PR-15). Rules:
   - a post-submit uncertainty is never `Failed`
   - `prepare` failures mean "not paid"
   - cancellation is propagated
   - already-paid, expired and insufficient funds map to the shared errors
   - fees are reported

   Running the same suite against all four providers turns REQ-provider-experience into
   something executable.
4. **Wallet lifecycle suite** (PR-08, PR-12). Each rule of SPEC-wallet-lifecycle becomes a
   test condition, organised as a state-transition model:
   - states: Rayl wallet, external setup, external connected, cleanup failed
   - events: choose, connect, cancel, back to Rayl, relaunch, crash mid-setup, credential
     access failure, fresh install

   It covers every valid transition and the forbidden ones. A traceability table maps spec
   sentences to tests.
5. **Pocket key suite** (PR-09, PR-10):
   - `PocketKeyStore` contract on each platform: add never replaces; an unreadable key
     stays unreadable and is never silently overwritten; deletion happens only on explicit
     action
   - passkey setup partitions: PRF supported or not, cancelled, several accounts
   - recovery: set it up again when the key is unreadable
6. **Input corpus** (PR-14). A golden file of real-world payment strings that
   `LightningInputParser` must classify correctly, run on every change. Add every string
   that failed or looked unusual (merchants, PoS terminals, LNURL services, other wallets'
   QR codes), together with the expected target or rejection reason.

### 6.3 Techniques

| Technique | Applied to |
|---|---|
| Equivalence partitioning and boundary value analysis | Amounts (0, 1 msat, sub-sat, min/max sendable, Auto Pay threshold ± 1 sat, threshold steps 500…100,000), invoice expiry (just before and after) |
| Decision tables | Confirmation policy; error-to-message mapping per provider |
| State-transition testing | Wallet lifecycle, pocket status, `PaymentUiState`, onboarding steps |
| Property-based testing | Fiat↔sats round trips and monotonicity; the parser never throws on arbitrary input; amount entry never produces negative or sub-sat amounts. Can start with hand-rolled generators; whether to add a library is open decision D-07. |
| Fault injection and error guessing | A network drop at each step (resolve, quote, send, await); an SDK exception during each phase; hostile LNURL responses (wrong amount, metadata mismatch, huge images, invalid ranges) |
| Pairwise (combinatorial) testing | Release checklist configurations: provider × platform × display currency × confirmation mode × locale |
| Exploratory testing (session-based) | Chartered 45–60 min sessions with notes kept in `qa/sessions/` |

### 6.4 Non-functional testing

**Performance (PR-13).** Recommended two-tier strategy. This answers the question raised in
the interview.

- *The field number is the truth; the lab number is the alarm.* Real-user percentiles
  across all devices are the right way to say how fast Rayl is. They arrive only after
  release, though, and mix device, network and version effects, so they can't stop a
  regression before it ships.
- A lab benchmark on fixed reference devices isn't meant to represent users. It is a
  controlled instrument: same device, same conditions, so when the number moves, the code
  caused it. Use it *relatively*, as in "no stage regresses more than X % against the last
  release baseline". Set X after measuring run-to-run noise. Avoid absolute budgets until
  field data exists.
- Report percentiles (p50/p75/p90), never averages. Latency is long-tailed, and averages
  hide the slow devices that matter most.
- **Split the staple path into machine stages**, and leave out the human time spent aiming
  the camera:
  1. process start → Scan ready (camera first frame)
  2. QR decoded → payment admitted
  3. admitted → quote ready
  4. confirmed or auto-submitted → outcome shown

  Some of these marks already exist as `RaylTiming` and `camera.*` trace sections. Turning
  them into platform trace sections (`androidx.tracing` and `os_signpost`) lets the same
  points feed both the lab benchmark and field metrics.
- **Field data in two steps:**
  1. Start with sources that need no change to the privacy policy: Google Play Android
     vitals (cold start rates) and Xcode Organizer/MetricKit launch metrics.
  2. Custom "scan → paid" percentiles need Rayl's own telemetry. The current
     [privacy policy](../distribution/privacy.md) says Rayl has no analytics, so this
     needs a product and legal decision first (opt-in, anonymous, aggregated). Open
     decision D-02.

**Security and privacy (PR-11, PR-20, PR-21):**
- static checks for secrets in logs and `toString`
- review of release-build logcat and Console output during the checklist run
- a backup and data-extraction review (`allowBackup=false` is already set)
- screen-capture behaviour on credential screens (O-05)
- checks that each privacy-policy claim matches observed behaviour
- dependency update review for the Breez SDK pins

A threat-model session for the pocket is proposed after Phase 2.

**Reliability and recovery (PR-04, PR-17):** interruptions and process death in each
payment and setup phase, offline start, flaky network, Breez sync delays, a cleanup
failure followed by a retry.

**Localisation (PR-22):** `verifyNativeLocalizations` in CI; a de/es pass in the checklist;
locale-specific number formats in amount entry and display.

**Compatibility:** device-matrix runs on Browserless App Live (§7), covering OS minimums,
OEM skins and camera stacks, passkey providers, and low-end hardware.

**Accessibility (PR-23):** an exploratory pass on Scan, confirmation and onboarding before
1.1; deeper work after 1.1.

### 6.5 Static testing

- **Spec and doc reviews:** QA reviews new or changed Linked Specs and `distribution/` docs
  for testability, ambiguity, and consistency with code.
- **Code review from a test perspective** on changes to money-path or lifecycle code. The
  review checks:
  - Is the change covered by the suites above?
  - Can an uncertain outcome become `Failed`?
  - Can a payment slip past confirmation?
  - Does a secret reach a log?
- **Existing gates stay:** ktlint, `verifyModuleDependencies`, `verifyNativeLocalizations`.

### 6.6 Regression and confirmation testing

- Every fixed defect gets a test at the lowest level that would have caught it, citing the
  risk ID where useful.
- **Regression selection:**
  - the full automated suite runs on every change (it is fast)
  - the manual checklist runs per release candidate
  - exploratory charters are chosen by which risks the release touched

### 6.7 Coverage

Coverage is used as **information, not a target**. Kover reports are generated in CI, and
QA reviews uncovered branches in Level 1 modules: `feature/payment-flow`, `core/payment`,
`providers/*/feature/payment`, `providers/rayl/integration/pocket`, `app/shared`. A
regression guard ("coverage must not drop") may be added for those modules once the
suites exist. There is no global percentage gate.

## 7. Test environments and data

| Environment | Purpose | Notes |
|---|---|---|
| JVM host | Unit, component and contract tests | Fast, deterministic, no network |
| Android emulator / iOS simulator | Platform integration tests | Keystore/Keychain behaviour; a signed simulator build is needed for the Keychain |
| Hosted Regtest (Lightspark) + regtest bank | SDK integration and manual pocket flows | No real funds. No real Lightning: routing, fees, LNURL and Lightning addresses need mainnet |
| Mainnet, small budget | Acceptance of every shipped provider | Dedicated test wallets; budget set per release (D-04) |
| Production backend | Price service | Synthetic `/health` and price-sanity check (proposal) |

**Devices**
- **Browserless App Live** gives manual access to many Android and iOS phones and has
  already found device-specific bugs. It is slow and manual, so it is used once per release
  candidate with the release checklist, not in automation.
- **Matrix for each App Live run (proposal, D-05):**
  - the oldest supported OS on each platform; passkeys need Android 9+ with Google Play
    services, so check how Rayl behaves below that
  - at least two Android OEMs (different camera stacks and skins)
  - one low-end Android
  - the oldest iPhone that runs iOS 18.5, and a current iPhone
  - whether App Live devices can complete real passkey flows (PR-10), to be checked once
- **Local Samsung SM-G970F:** the lab reference device for performance benchmarks, and for
  quick checks.

**Test data and test doubles**
- A shared test-fixtures module (proposed `core/testing`, test-only dependency) containing:
  - a BOLT11 invoice factory that signs invoices with lightning-kmp, with chosen amount,
    expiry, payment hash and description
  - a fake LNURL server built on `MockEngine`, including hostile responses
  - a fake price provider and a test clock
  - fake secure stores
  - a recording `PaymentSettlement`
- The golden input corpus (§6.2) and golden backend JSON.
- No real credentials or recovery phrases in the repository. Only public test vectors,
  such as the standard BIP39 "abandon … about" phrase.

**Mainnet acceptance protocol (outline):**
- Fund dedicated test wallets with a fixed budget.
- Pay at least one example of each input type: a BOLT11 with an amount, an amountless
  BOLT11, a Lightning address, an LNURL QR code.
- Pay in at least one fiat currency.
- Hit one Auto Pay case and one confirmation case.
- Rescan an invoice that is already paid.
- Run all of the above with each shipped provider on both platforms.
- Record results in the release test summary.

## 8. Automation and CI

| Stage | Trigger | Contents | Gating |
|---|---|---|---|
| **PR gate** | Push or PR to `main` | ktlint, module dependencies, localisations, all host unit/component/contract tests, backend tests, Android debug build, iOS simulator build | Must pass to merge |
| **Nightly** | Schedule | Everything in the PR gate, plus: `iosSimulatorArm64Test`; Android instrumented storage tests; regtest SDK integration; Kover report; Breez pin consistency check | Failures become issues; never blocks `main` retroactively |
| **Performance** | Weekly, release tag, or PR label | `perfCheck` (macrobenchmark), extended with the staple-path stages | Release gate; alerts on relative regression |
| **Release** | Release branch (1.1 onwards) | PR gate plus nightly on the release branch; release-build smoke | Exit criteria in §9 |

A prerequisite for all of this is **restoring CI (PJ-01)**. Until then, the founder runs
the PR gate locally:

```shell
./gradlew ktlintCheck verifyModuleDependencies verifyNativeLocalizations testAndroidHostTest :backend:rayl:test
```

Test design rules for automated tests:
- deterministic, with no real network or time in host tests (use a test clock and
  virtual-time coroutines)
- one behaviour per test, named in domain language
- fakes over mocks
- tests document *why* a case matters when that isn't obvious, citing `PR-xx`, `O-xx` or
  a spec ID only where it helps

## 9. Entry, exit and suspension criteria

**Pull request (once CI is back)**
- *Entry:* builds locally.
- *Exit:* PR gate green. Money-path or lifecycle changes include or update tests in the
  focus suites.

**1.0 (already in store review).** Only a smoke test of the signed release build: install,
onboard, and one small mainnet payment per shipped provider on each platform. Defects go to
1.1 unless they are S1.

**Release candidate (1.1 onwards)**
- *Entry:*
  - release branch cut with provider gating applied
  - PR gate and nightly green on that branch
  - the release build uses the Breez passkey relying party (`keys.breez.technology`), and its
    association files list the shipped app IDs and signing certificate, if the Rayl wallet
    ships (PJ-05)
  - release notes list the changed areas
- *Exit:*
  1. No open S1 or S2 defects. Open S3 defects reviewed and accepted by the stakeholder.
  2. Every Level 1 risk has its planned automated tests in place and passing. Remaining
     risk is written down and accepted in the test summary report.
  3. The regression checklist passes on the App Live device matrix on both platforms.
  4. The mainnet acceptance protocol passes for every shipped provider on both platforms.
  5. No staple-path stage regresses beyond the agreed tolerance against the previous
     baseline.
  6. `distribution/` (terms, privacy, store listings) matches the shipped scope and
     behaviour.
  7. The test summary report is written and approved.

**Suspension and resumption.** Suspend release testing if any of these happens:
- an S1 defect
- an environment outage (regtest or mainnet services, the backend)
- the build is not the signed release configuration

Resume on a new candidate build, re-running the checklist sections it affects.

## 10. Defect management

Defects are tracked as Linear issues in the Mobile team (`MOB`) with the `Bug` label. The
workspace has no severity or risk labels, so the severity (`S1`–`S4`) and the risk ID go at
the top of the description. They are prioritised with the stakeholder.

| Severity | Meaning | Examples |
|---|---|---|
| **S1 Critical** | Funds lost or misdirected, an unintended or duplicate payment, a credential or key leaked or lost, or paying impossible for everyone | Auto Pay ignores the threshold; the pocket key is replaced |
| **S2 High** | Paying is blocked for a common case or provider, the wrong outcome is shown, the wallet lifecycle is violated without exposure, or a crash on the main path | Lightning addresses fail on iOS; an Unknown outcome is shown as Failed |
| **S3 Medium** | A workaround exists, a crash off the main path, a provider or platform parity deviation, a noticeable performance regression | Different error copy for NWC |
| **S4 Low** | Cosmetic, copy, minor layout | Truncated German label |

Each report contains: build and commit, platform and device, provider, steps, expected
versus actual, and evidence (logs, `RaylTiming`, screenshots).

**Who fixes (from 2026-11-02).** QA fixes defects too, so testing doesn't wait for fixes:
file the issue, fix it with a regression test in its own commit that names the issue, then
close the issue. A defect is filed even when it's fixed minutes later. Questions about the
intended behaviour still go to the stakeholder before the fix.

## 11. Metrics and reporting

Kept to what drives decisions:
- **Risk coverage:** for each Level 1 risk, whether its planned tests exist and pass. This
  is the main progress indicator.
- **Defects:** open by severity, and found per release by risk area (shows clustering).
- **Staple-path stage timings:** lab trend per release; field percentiles once available.
- **Suite health:** duration and flaky-test count. The host suite should stay under
  ~2 minutes.

A **test summary report** is produced per release candidate in `qa/reports/`.

## 12. Roadmap (phased backlog)

The phases are ordered by risk and aimed at 1.1. Exact dates depend on the 1.1 target
(D-01). Items inside a
phase are small, independently mergeable slices.

**Phase 0: Foundation**
- P0.1 Restore CI: publish or pin `nwc-kmp` (PJ-01). *Owner: Nicola.*
- P0.2 Shared test fixtures module: invoice factory, fake LNURL server, fake prices, test
  clock, fake stores, recording settlement.
- P0.3 Kover report in CI as information; add `iosSimulatorArm64Test` to the iOS workflow.
- P0.4 Agree the open decisions in §15, and specify SI-01 (Auto Pay and fees) together.

**Phase 1: Money correctness and payment input (PR-01 to PR-07, PR-14, PR-18)**
- P1.1 Coordinator component suite: happy paths for each source and each outcome.
- P1.2 Auto Pay decision table, on the policy and through the coordinator (resolve O-01
  first). Fee conditions follow SI-01.
- P1.3 Amount BVA and property tests: conversion, rounding, sub-sat, LNURL range,
  locale input.
- P1.4 Duplicate-payment tests: operation guard, paid-hash lookup, idempotency key,
  cancellation during settle (O-02).
- P1.5 Settlement contract suite run against all four providers.
- P1.6 Hostile LNURL fixtures (PR-05).
- P1.7 Fee display checks per provider: a shown fee matches the fee reported as paid
  (PR-06).
- P1.8 Price service tests (PR-18): the client/backend wire contract pinned with golden
  JSON, and client behaviour when the backend is down, returns 503, or returns missing,
  zero or non-finite prices (fiat entry is blocked; no amount is converted with a bad rate).
- P1.9 Parser input corpus (PR-14), plus a property test that the parser never throws on
  arbitrary input.

**Phase 2: Wallet and credential safety (PR-08 to PR-12)**
- P2.1 Wallet lifecycle state-transition suite with a traceability table to
  SPEC-wallet-lifecycle.
- P2.2 Platform `PocketKeyStore` and credential-store tests (Android instrumented, iOS
  simulator).
- P2.3 Passkey setup partitions and recovery paths.
- P2.4 Secret-hygiene checks plus a decision on Android screen protection (O-05).
- P2.5 Pocket threat-model session (QA + Nicola).

**Phase 3: System and acceptance**
- P3.1 Release regression checklist, risk-based, about 45 minutes per platform. It also
  serves the App Live device runs.
- P3.2 Exploratory charters: interruptions, real-world QR codes in the field,
  passkey environments, locales, first-run experience.
- P3.3 Nightly regtest SDK integration test (PR-19).
- P3.4 Mainnet acceptance protocol plus budget (D-04).
- P3.5 Static review of `distribution/` against the 1.1 scope (PR-21, O-04).

**Phase 4: Performance**
- P4.1 Trace sections for the staple-path stages on both platforms.
- P4.2 Extend `perfCheck` with those stages; measure noise; set relative tolerances.
- P4.3 Field metrics: platform vitals now; custom telemetry only after D-02.

**After 1.1**
- Decide on UI automation (D-03). Payment input would come from an E2E-only launch
  argument that stands in for the camera, since automated runs can't scan.
- Accessibility depth.
- Revisit coverage guards.

## 13. Roles and responsibilities

| Role | Who | Responsibilities |
|---|---|---|
| Product owner, approver | Nicola | Priorities, risk acceptance, decisions in §15, sign-off on exit criteria |
| Developer | Nicola | Fixes, testable design, local PR gate until CI is back, signed commits |
| QA / SDET | Claude (agent) | This plan, risk analysis, test design, automated tests below the UI, test-focused reviews, checklists and charters, test summary reports. On-device runs only when asked. |
| Device and mainnet tester | Nicola, by default | Runs the checklist, charters and mainnet protocol; QA prepares them and analyses the results |

## 14. Project risks

See [product-risks.md, project risks](product-risks.md#project-risks) (PJ-01 to PJ-07).

## 15. Decisions and open questions

**Decided (interview, 2026-11-01)**
- D-00a The plan targets a release with **Rayl's wallet plus the external wallets**.
  `release/1.0` (in store review) builds only Blink and NWC. Its Spark sources are not
  included in the build. So this scope means **1.1**: `main` has Blink, NWC, Spark and the
  built-in Rayl wallet. Its release builds use Breez's passkey relying party, registered on 2026-11-01 (PJ-05).
- D-00b Top quality priorities: **money correctness** and **wallet/credential safety**.
- D-03 **UI automation: decide later** (revisit after 1.1). Until then, system testing is manual, and
  automation targets the levels below the UI.

**Decided (review, 2026-11-01)**
- D-08 **Test effort targets `main` and 1.1.** 1.0 is in store review, 1.1 follows right
  after, and 1.0 gets only a release-build smoke test.
- D-09 **Device coverage comes from Browserless App Live**, with manual checklist runs once
  per release candidate.

**Decided (kickoff interview, 2026-11-01)**
- D-01 **1.1 ships in about two weeks** (target 2026-11-15). The phases are cut to fit in
  the [1.1 release test plan](releases/1.1/release-test-plan.md).
- D-10 **The custom parser and the backend fiat rates stay.** Neither is being replaced by
  the Breez SDK, so both get normal test investment (P1.8, P1.9).
- D-06a **O-01: confirmation is required at exactly the threshold.** The code is right; the
  "Above 10,000 sats" copy is wrong.
- SI-01 **The threshold caps amount + fee where the fee is known** (appendix B).

**Decided (review of open questions, 2026-11-02)**
- D-02 **Telemetry is out of scope for 1.1.** High priority afterwards: privacy-aligned
  insight into what happens in the app (MOB-92).
- D-04 **Mainnet budget: 1,000 sats per release candidate.**
- D-05 **The App Live matrix in §7 is accepted.**
- D-06 O-05: **no screen-capture protection for now.** It blocks testing on remote devices
  and slows store review, which matter more right now. A strategy is still to be found (code
  comment in the Android app). O-08: **lowercase capitalised Lightning addresses.** O-09:
  **every wallet reports an already-paid invoice as success marked "already paid"**, when
  the session history knows it or the wallet's response says so unambiguously (NWC may not
  allow this).
- D-07 Random-input (property) tests use a widely used library rather than hand-written
  generators.
- O-10: **remove the legacy fallbacks.**
- From 2026-11-02 QA also fixes defects. Every defect is filed in Linear first and closed when
  the fix is committed (§10).

**Open (stakeholder input needed)**
- SI-01 follow-up: whether fees are estimated by default (appendix B).

---

## Appendix A: Observations from analysis (to confirm)

These came from reading the code during planning. They are **not confirmed defects**: each
one needs a test or a product decision before it becomes a defect or is closed.

| ID | Observation | Risk | Next step |
|---|---|---|---|
| O-01 | The settings copy says "Above 10,000 sats", but `PaymentConfirmationPolicy` asks for confirmation at `amountSats >= thresholdSats`, i.e. also at exactly 10,000. The code errs on the safe side, but the copy and code disagree at the boundary. | PR-02 | Decided (D-06a): the code is right. Boundary pinned in `PaymentConfirmationPolicyTest`. The onboarding label "Auto-pay up to …" is wrong too; defect drafted ([1.1 status](releases/1.1/status.md)) |
| O-02 | `SparkExperience` calls `resetPaymentSession()` when the wallet state becomes `NoWallet` or `ResetRequired`, without the `isSubmitting` guard that the Rayl and Blink exit paths use. `resetSession()` cancels the in-flight operation, so if that state change can happen during `settle`, the outcome could be neither shown nor recorded. | PR-04 | Check reachability; write a component test |
| O-03 | Known FIXME in `App.kt`: on a Spark cold start, a payment deep link can be rejected as "connect to pay" before the asynchronous restore finishes. | PR-14, PR-17 | Resolved: deep links were removed (§3.1) |
| O-04 | `terms.md` and `privacy.md` describe Rayl as a non-custodial client for external wallets only. They don't mention the built-in pocket, passkeys, the device-held key copy, or Breez. | PR-21 | Review before release; release exit criterion 6 |
| O-05 | Android has no screen-capture protection on any screen since `975f45f9`, including Spark recovery-phrase, Blink API-key and NWC URI entry. iOS marks the phrase `privacySensitive()`. This is both a security question and a parity deviation. | PR-11, PR-16 | Decided (D-06): no protection for now; strategy to be found |
| O-06 | Auto Pay compares the invoice amount with the threshold. Routing fees are not part of the decision. | PR-06 | Became SI-01 (appendix B) |
| O-08 | A Lightning address with uppercase letters in the user part (`Alice@example.com`) is rejected as unrecognised. `LightningAddress.parse` accepts it, but `DefaultLnurlParser` rejects it. Phone keyboards capitalise the first letter. LUD-16 allows only lowercase; many wallets lowercase instead of rejecting. | PR-14 | Decided (D-06): lowercase. MOB-89 |
| O-09 | Rescanning an already-paid invoice: Blink returns `Paid(wasAlreadyPaid = true)` (success screen), Rayl and Spark return `Failed(AlreadyPaid)` (error screen), contrary to REQ-provider-experience. | PR-15 | Decided (D-06): success marked "already paid" in every wallet that can tell. MOB-90 |
| O-10 | Legacy fallback, contrary to the pre-release policy: `SparkOnboardingState` treats restored wallets as having seen the intro screens. (The `"recent"` tab mapping this row also named was already removed in `96980bef`; the observation was out of date.) | none | Decided: remove. MOB-91 |
| O-07 | Client-side duplicate detection for re-scanned BOLT11 invoices uses only the in-memory session history, which keeps the last 10 payments (`MAX_RECENT_PAYMENTS`). After a restart, or once more than 10 payments have been made, protection depends on each wallet rejecting an already-paid invoice. | PR-03 | Verify per provider (regtest for the pocket and Spark, mainnet for Blink and NWC) |

## Appendix B: Specification issues

These are gaps in the test basis: the intended behaviour isn't defined clearly enough to
test. Each one needs a product decision. Tests that depend on it are blocked until then.

### SI-01: Fees under Auto Pay (PR-02, PR-06)

**Problem.** Auto Pay skips showing the fee so it stays fast. The threshold is meant to cap
what Rayl pays without asking. Example: threshold 1,000 sats, invoice 100 sats, fee 901
sats, total 1,001. That payment should *not* be auto-approved. Today the decision looks at
the invoice amount only (`PaymentConfirmationPolicy`), and the settings copy doesn't say
whether fees count.

**What each wallet can know** (checked in the code, 2026-11-01):

| Wallet | Fee known before submitting? | Can the fee be capped at submission? |
|---|---|---|
| Rayl pocket | **Yes.** `RaylPaymentSettlement.prepare` quotes every payment (`prepareSendPayment`) *before* the confirm/Auto Pay decision, so the estimate is already there and costs no extra time. | To be checked: `feePolicy` is passed as `null` |
| Spark | **Yes**, same as the pocket (`SparkPaymentSettlement`) | To be checked, same as the pocket |
| Blink | **No.** Rayl doesn't probe; the fee is known only after payment (`feesPaid`). Blink's API offers a fee probe, at the cost of a round trip. | To be checked against the Blink API |
| NWC | **No.** NIP-47 `pay_invoice` has no fee capability; the fee is known only afterwards (`fees_paid`). | No |

So a fee-aware Auto Pay costs no speed on the two Breez-based wallets. The trade-off
between speed and certainty only exists for Blink (probe or not) and NWC (can't know).
[REQ-provider-experience](../specs/REQ-provider-experience.md) allows differences that come
from an unsupported capability, which this is.

**Questions to decide:**
1. Does the threshold limit the amount or **amount + fee**? Your example implies amount +
   fee.
2. When the fee is known and amount + fee is over the threshold: ask for confirmation and
   show the fee, or refuse?
3. When the fee can't be known beforehand (NWC; Blink without a probe), choose one of:
   - count a fee allowance against the threshold
   - probe (Blink only)
   - accept the uncertainty and say so in the setting
   - another rule
4. Where a fee is shown, is it labelled an estimate before payment and the actual fee
   after?

**Decision (2026-11-01).** The threshold caps **amount + fee where the wallet knows the fee
before submitting**:
- Rayl wallet and Spark: when amount + quoted fee reaches the threshold, ask for
  confirmation and show the fee.
- Blink and NWC: compare the amount only, and the setting says that fees may be added.

Still open: question 4 (estimate vs actual labelling), and whether Blink should probe later.

**Reopened (2026-11-02).** The owner leans towards not estimating fees by default (most
users don't care about a few sats, and it saves time), with fee estimation as an opt-in
setting where the wallet supports it. The decision above is on hold until this is settled.

The rule spans the shared flow and every provider's settlement, so it probably qualifies as
a Linked Specs `SPEC-*` record (owner: Nicola). Tests follow from it: decision table
conditions in P1.2 and fee checks in P1.7.
