# Rayl product risk register

Status: draft v0.1, 2026-11-01. Owner: QA. Approver: Nicola Susca.
Used by: [Master test plan](test-plan.md).

This register drives where test effort goes. Each risk names what could go wrong for a
user, how likely and how bad it is, and how the plan mitigates it. Tests and defect
reports cite risk IDs (`PR-xx`) where the link adds useful rationale.

## Scales

**Impact**
- **Critical:** loss or misdirection of funds, an unintended payment, or a credential
  or key that leaks or is lost. Usually irreversible.
- **High:** the user cannot pay in a common situation, sees the wrong outcome, or loses
  trust.
- **Medium:** a workaround exists, or the problem is off the main path.
- **Low:** cosmetic.

**Likelihood (H/M/L)** combines several factors:
- complexity and how often the code changes
- current test coverage
- dependence on external systems
- the defect history in `git log`

**Risk level**
- **Level 1:** Critical impact at any likelihood, or High impact with M/H likelihood.
- **Level 2:** High impact with L likelihood, or Medium impact with M/H likelihood.
- **Level 3:** everything else.

| Level | Test depth |
|---|---|
| 1 | Systematic techniques at two or more test levels, automated regression on every change, and an explicit release exit criterion |
| 2 | Systematic tests at one automated level plus a release checklist item |
| 3 | Exploratory or checklist coverage only |

Stakeholder priority (interview, 2026-11-01): **money correctness** and **wallet/credential
safety** come first. Ties are broken in their favour.

## A. Money correctness

| ID | Risk | L | Impact | Level | Mitigation (see test plan §6) |
|---|---|---|---|---|---|
| PR-01 | **Paid amount differs from what the user intended.** Possible causes: fiat→sats conversion, a stale or wrong rate, rounding, msat/sat truncation, locale decimal separators in amount entry, LNURL min/max handling, an amountless-invoice override. | M | Critical | 1 | Boundary value analysis (BVA) and property-based tests on conversion and amount entry; coordinator component suite; pay in fiat during mainnet acceptance |
| PR-02 | **Payment submitted without the confirmation the user's settings require.** Covers the Auto Pay threshold boundary (amount *and* fee, see SI-01), preset Hub targets, manual entry, and preferences not yet loaded at cold start. | M | Critical | 1 | Decision-table tests for the policy *and* for its use in the flow; release checklist |
| PR-03 | **Duplicate payment.** Possible causes: a double scan, a retry after an unknown outcome, or a re-scan after an app restart. | M | Critical | 1 | Single-operation guard tests, paid-hash lookup tests, idempotency-key tests, and an "already paid" check per provider |
| PR-04 | **Payment outcome misreported.** An unknown outcome is shown as failed (which invites a retry), success is shown before settlement, or an in-flight payment is cancelled by a session reset, navigation, or process death and its outcome is lost. | M | Critical | 1 | Settlement contract suite (Paid/Failed/Unknown classification) for every provider; interruption tests; exploratory charter |
| PR-05 | **Payment goes to a different recipient or amount than displayed.** The LNURL invoice doesn't match the requested amount or metadata, an endpoint is malicious, or the domain/description/image display is spoofed. | L | Critical | 1 | Tests against a fake LNURL server returning hostile responses; `LnurlInvoiceResolver` BVA |
| PR-06 | **Fees surprise the user**, measured against what each wallet can actually know. Where Rayl shows a fee, it must be correct; a quote is an estimate unless the wallet guarantees it. The bigger gap is that **Auto Pay may pay amount + fee above the user's threshold**: Auto Pay skips the fee display for speed, and the rule for fees under Auto Pay is not specified. | M | High | 1 | Shown fees match the fees reported as paid, per provider. Auto Pay fee tests follow the SI-01 decision ([test plan appendix B](test-plan.md#appendix-b-specification-issues)): amount + fee where the fee is known. They wait for the implementation. |
| PR-07 | **Expired, already-paid or insufficient-funds invoices are mishandled**, giving a wrong message or a stuck loading state. | M | Medium | 2 | Error-mapping tests per provider; real SDK error classification in the nightly regtest run |

## B. Wallet and credential safety

| ID | Risk | L | Impact | Level | Mitigation |
|---|---|---|---|---|---|
| PR-08 | **External credentials retained or restored**, contrary to [SPEC-wallet-lifecycle](../specs/SPEC-wallet-lifecycle.md). Cases: Back to Rayl, cancelled setup, a crash mid-setup, an iOS Keychain item surviving reinstall, a cleanup failure. | M | Critical | 1 | State-transition suite traced to each spec rule; platform storage tests on device and simulator; release checklist |
| PR-09 | **Pocket funds become inaccessible.** The key copy is lost, overwritten, silently replaced, or erased by cleanup. The Keystore/Keychain is invalidated (lock-screen change, OS update, restore to a new device). The passkey derives a different key (relying-party (RP) change, wrong credential chosen). | M | Critical | 1 | `PocketKeyStore` contract tests on both platforms; restore-on-new-device acceptance test; the release RP is final before the Rayl wallet ships (1.1) |
| PR-10 | **Passkey onboarding fails or misleads on real-world setups.** Cases: no PRF support, third-party password managers, several Rayl passkeys, a cancelled sheet, no screen lock, devices without Google Mobile Services, iCloud Keychain off. | H | High | 1 | Equivalence partitions of passkey environments; exploratory charter on a device matrix; unit tests of the fallback to external wallets |
| PR-11 | **Secrets exposed.** Possible channels: logs (timing marks carry error text, regtest invoice logging), crash reports, Android screenshots and the Recents view (no screen-capture protection on Android), `toString`, backups. | L–M | Critical | 1 | Static checks (grep rules plus a release-build log review); redaction tests; decision on Android screen protection (O-05) |
| PR-12 | **Wrong wallet mounted, or two wallets own a connection.** Examples: a wrong restore after restart, or a temporary credential-access failure treated as "no wallet", so Rayl silently goes back to its own wallet. | L | High | 2 | `RaylSelection` and runtime state tests; checklist |

## C. Core experience and performance

| ID | Risk | L | Impact | Level | Mitigation |
|---|---|---|---|---|---|
| PR-13 | **Cold start → Scan → paid gets slower.** Likely causes: pocket early start, Breez connect/sync, camera initialisation, main-thread work. `main` changes fast. | H | High | 1 | Two-tier performance strategy (lab regression benchmark plus field percentiles); stage budgets |
| PR-14 | **A real-world payment request can't be scanned or parsed.** Variants: uppercase BOLT11, `lightning:` URIs, BIP21 with `lightning=`, bech32 LNURL and LUD-17 schemes, Lightning addresses with odd case or spacing, dense, inverted or damaged QR codes, poor light. | M | High | 1 | Parser unit tests; a golden corpus of real-world payment strings, extended whenever one fails or looks unusual; a property test that the parser never throws; camera exploratory charter |
| PR-15 | **Providers diverge** for the same scenario (flow, copy, extra steps), contrary to [REQ-provider-experience](../specs/REQ-provider-experience.md). | H | Medium | 2 | Settlement contract suite; provider-parity pass in the release checklist |
| PR-16 | **Android and iOS diverge.** The native UIs differ, and the Swift code has no automated tests. | H | Medium | 2 | The same checklist on both platforms; keep logic in common Kotlin, where it is testable |
| PR-17 | **Interruptions break a flow.** Examples: background/foreground, rotation, process death, a phone call, or a network loss during scan, confirm, pay or setup. | M | High | 1 | Interruption charter; component tests for cancellation; Android configuration-change tests |

## D. Dependencies and release configuration

| ID | Risk | L | Impact | Level | Mitigation |
|---|---|---|---|---|---|
| PR-18 | **The price service is down or wrong.** If all sources are stale it returns 503, and it runs as one Railway instance. Fiat entry and presets break, or estimates are wrong. | L–M | High | 2 | Backend aggregation tests; wire contract pinned with golden JSON; client degradation tests (backend down, 503, invalid prices); fiat→sats conversion tests (PR-01); release checklist item for fiat entry |
| PR-19 | **An external dependency changes or fails.** Examples: a Breez SDK upgrade (pinned separately in Gradle and Xcode), the Blink GraphQL schema, NWC relays, LNURL servers. | M | High | 1 | Nightly regtest run against the real SDK; a check that the Gradle and Xcode SDK pins match; Blink and NWC contract fixtures |
| PR-20 | **The release build is misconfigured.** Examples: wrong network, a missing Breez key, wrong passkey RP or association files, debug tools or timing logs shipped, providers gated incorrectly on the release branch. | L | Critical | 1 | Release smoke checklist run on the signed release build; static configuration review |

## E. Compliance and content

| ID | Risk | L | Impact | Level | Mitigation |
|---|---|---|---|---|---|
| PR-21 | **Store listing, terms or privacy policy don't match the shipped product.** Today they describe only an external-wallet client, with no built-in pocket or passkeys (O-04). | H | High | 1 | Static review of `distribution/` against the release scope as a release exit criterion |
| PR-22 | **Localisation or formatting errors in en/de/es.** Missing strings, truncation, number formats in amounts. | M | Medium | 2 | `verifyNativeLocalizations`; locale pass in the checklist; amount-entry tests per locale |
| PR-23 | **Accessibility gaps** on Scan, confirmation and onboarding (TalkBack/VoiceOver, Dynamic Type). | M | Medium | 3 | Exploratory pass; revisit after 1.1 |
| PR-24 | **Hub contacts or presets are lost or corrupted.** | L | Medium | 3 | Repository tests (some exist); checklist |
| PR-25 | **An update loses or misreads data an earlier version stored.** Examples: a 1.1 user's Blink or NWC connection, Hub contacts or Auto Pay threshold gone after an update; Rayl's wallet key copy or the Spark vault unreadable; a renamed key restores the wrong wallet. Added 2026-11-02, when 1.0 reached testers; since 2026-11-06 about 1.1, the first public release. | M | High | 1 | Tests that open 1.1-shaped data with the current code (settings on the host, encrypted stores on a device); static diff against the last release branch |

## Project risks

These threaten the test effort itself rather than the product.

| ID | Risk | Mitigation |
|---|---|---|
| PJ-01 | ~~CI is disabled: `nwc-kmp` 0.3.3-SNAPSHOT exists only in the maintainer's `mavenLocal`. Nothing runs on push or PR.~~ Resolved 2026-11-03 (MOB-100): the Nostr SDK is built from source in `nostr/`, and the build no longer reads `mavenLocal` or snapshot repositories. | Keep the build on released artifacts and in-repo projects only |
| PJ-02 | A one-person team means low tester independence, and manual testing competes with feature work. | Keep the manual checklist short and risk-based; QA designs and automates; the founder runs device and mainnet passes |
| PJ-03 | QA (an AI agent) works in sessions and doesn't run on-device or E2E tests unless asked. | Continuity lives in `qa/`; on-device work only on request |
| PJ-04 | Mainnet testing needs real funds and discipline. | Fixed per-release budget and a written protocol (test plan §7) |
| PJ-05 | ~~The Rayl wallet in 1.1 depends on Breez registering the passkey RP, so the release date is uncertain.~~ Resolved 2026-11-01: Breez lists the release apps at `keys.breez.technology`, and release builds use it. | Keep the RP and association check in the release entry criteria |
| PJ-06 | The first store review takes long, so `main` has moved well past 1.0, and **1.1 ships right after 1.0**. Testing the frozen 1.0 branch in depth would go to a build users will barely run. Release branches also remove unready providers, so each shipped build differs from `main`. | Test effort targets `main` and the 1.1 release. 1.0 gets only a release-build smoke test. Each release gate runs on its own release branch. |
| PJ-07 | Device coverage: Browserless App Live gives manual access to many Android and iOS phones and has already found device-specific bugs. It is slow and manual, can't run in the automated suite, and so is used rarely. | Device-matrix runs on App Live with the release checklist, once per release candidate. One local reference device for lab benchmarks. |
