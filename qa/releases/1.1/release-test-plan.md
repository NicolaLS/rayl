# 1.1 release test plan

Status: draft v0.1, 2026-11-01. Owner: QA. Approver: Nicola Susca.
Parent: [master test plan](../../test-plan.md). This document only says what the master plan
doesn't: what 1.1 tests, by when, and what is cut to fit the two weeks (D-01).
Progress is tracked in [status.md](status.md).

## 1. Release under test

| | |
|---|---|
| Version | 1.1.0 (Android `versionName` is still 1.0.0 on `main`) |
| Target | Stores by **2026-11-15** (D-01: about two weeks from 2026-11-01) |
| Branch | `release/1.1`, cut from `main` (not yet cut) |
| Wallets | Rayl's built-in wallet (default, Breez relying party `keys.breez.technology`), plus Blink, NWC and Spark as external wallets. Assumption: all four ship; the release branch removes any that aren't ready. |
| Platforms | Android and iOS |
| New since 1.0 | Rayl's built-in wallet (passkeys, pocket key, add funds, balance), Spark as an external wallet, Spark recovery phrase entered word by word, Spark paying through the shared flow, deep links removed, fee-aware Auto Pay (SI-01, to be built) |

Assumption to confirm: "ships" means submitted to both stores, so the release candidate
must be cut early in week 2.

## 2. Schedule

| When | QA | Nicola |
|---|---|---|
| **Week 1** (Nov 2–8) | Automated Level 1 tests below the UI (Must items, §3); static reviews; draft the release checklist and mainnet protocol | Build fee-aware Auto Pay (SI-01) and fix the O-01 copy; restore CI or run the local gate before merging; decide D-04, D-05, O-05 |
| **RC1 cut** (target Nov 10) | Entry check (master plan §9) | Cut `release/1.1`, apply provider gating, build signed release builds |
| **Week 2** (Nov 10–14) | Analyse runs and triage defects; confirmation tests for fixes; test summary report | Run the checklist on App Live (both platforms), the mainnet protocol and the exploratory charters, with QA preparing each |
| **Go/no-go** (Nov 14) | Report residual risk against the exit criteria | Accept the residual risk or hold the release |

## 3. What gets tested for 1.1

The master roadmap (§12) doesn't fit into two weeks. This is the cut, by risk.

**Must (release gate)**

| # | Item | Risks | Roadmap |
|---|---|---|---|
| M1 | Auto Pay decision table on the policy and through the flow, including the threshold boundary (O-01) and the fee-aware conditions once built | PR-02, PR-06 | P1.2 |
| M2 | Payment flow component tests: each source and each outcome through `PaymentFlowCoordinator` | PR-01–PR-04 | P1.1 |
| M3 | Settlement classification on all four wallets: an uncertain outcome is never `Failed`; a `prepare` failure means not paid | PR-04, PR-15 | P1.5 (core) |
| M4 | Duplicate payment: the single-operation guard, and O-02 (a Spark session reset during settle) | PR-03, PR-04 | P1.4 (part) |
| M5 | Fiat→sats conversion and rounding: boundary values | PR-01 | P1.3 (part) |
| M6 | Wallet lifecycle: trace each SPEC-wallet-lifecycle rule to a test; fill the gaps | PR-08, PR-12 | P2.1 |
| M7 | Pocket key: an unreadable key is never overwritten, and an added key never replaces another (common code) | PR-09 | P2.2 (common part) |
| M8 | Static reviews: secrets in logs and `toString`, release configuration, and that the Gradle and Xcode Breez SDK pins match | PR-11, PR-19, PR-20 | P2.4, PR-20 |
| M9 | `distribution/` review against the 1.1 scope | PR-21 | P3.5 |
| M10 | Release regression checklist, run on App Live on both platforms | PR-13–PR-17, PR-22 | P3.1 |
| M11 | Mainnet acceptance protocol and its run | PR-01–PR-07 | P3.4 |
| M12 | Test summary report | all | §11 |

**Should (if time allows, in this order)**
1. S1 Parser input corpus and a never-throws property test (PR-14, P1.9). It is cheap.
2. S2 Price client degradation tests and golden JSON (PR-18, P1.8).
3. S3 Exploratory charters: interruptions (PR-17) and passkey environments (PR-10).
4. S4 Hostile LNURL fixtures (PR-05, P1.6).
5. S5 Fee display matches the fee paid, per wallet (PR-06, P1.7).
6. S6 Amount entry per locale (PR-01, PR-22).

**Not in 1.1** (back to the master roadmap):
- the shared fixtures module: fakes stay local and are extracted once duplication hurts
- platform credential-store tests on an emulator or simulator (P2.2 platform part); the
  checklist covers reinstall and restore instead
- the nightly regtest run (P3.3), Kover in CI (P0.3), the threat model (P2.5)
- performance (Phase 4), except one lab measurement of `perfCheck` on the Samsung as the
  1.1 reference, if Nicola runs it

## 4. Exit criteria for 1.1

The master plan §9 applies, with one change to criterion 2, since not every Level 1 risk
can be fully covered in two weeks:

> 2'. The Must items M1–M8 are done and passing. Every Level 1 risk that is not fully
> covered is listed as residual risk in the test summary report, and Nicola accepts it.

## 5. Dependencies on Nicola

| Item | Needed by | Blocks |
|---|---|---|
| Fee-aware Auto Pay built (SI-01) | Nov 8 | M1 fee conditions |
| O-01 copy fixed ("Above" → "From") | RC1 | M10 |
| Local PR gate before each merge, or CI restored (PJ-01) | Ongoing | Regression on every change |
| D-04 mainnet budget, D-05 App Live matrix, O-05 screen protection | Nov 8 | M10, M11, M8 |
| `release/1.1` cut with provider gating | Nov 10 | M10, M11 |
| A Linked Specs record for the SI-01 rule (recommended) | Before the feature merges | Traceability for M1 |
