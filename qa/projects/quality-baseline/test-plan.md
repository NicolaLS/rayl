# Test project 2: quality baseline

Status: **in progress**, started 2026-11-06. Owner: QA. Approver: Nicola Susca.
Strategy: [master test plan](../../test-plan.md), especially §6.8 (UI automation) and §6.9
(wallet test environments). Defects: Linear `MOB`, label `Bug` (master plan §10).

## Goal

1.1 is Rayl's first public release and is in store review. Its shared Kotlin logic is well
tested (274 host tests, also run as iOS code), but:
1. Every wallet service is a stub. No test runs Rayl's wallet code against a real Blink, NWC or
   Breez service.
2. There is no UI automation, and almost no platform test: 12 Android storage tests, nothing
   for Android-only logic, screens, flows or the iOS Keychain.
3. CI skips Android Lint, the release build, the device tests and release branches.
4. Nobody checks the store build before users get it, and crashes in the field aren't visible.

This project closes those gaps, in this order. Each item ends in commits with green tests, so
stopping after any item leaves a working state.

## Work items

| # | Item | Done when | Status |
|---|---|---|---|
| **0** | **1.1 smoke checklist** (time-critical: 1.1 can be approved any day). At most 20 minutes per platform on the store build: install, Rayl's wallet, one payment per wallet, rescan, connect and remove a wallet, a second language, legal links; plus MN-1, MN-2, MN-4 of the mainnet protocol. Nicola runs it before releasing 1.1 (manual or phased release in both stores). | Checklist in `qa/checklists/smoke.md`; Nicola has run it | Written (`8dc59fb9`); run pending |
| **1** | **Wallet test environments and integration tests** (§6.9) | | |
| 1a | NWC: revive the Blip harness (bitcoind, Core Lightning + `cln-nip47`, nostr relay) under `tools/`; integration tests of `NwcWallet` against it: pay, already paid, insufficient funds, expired, fee reported, lookup of a pending payment (MOB-9) | Starts with one script; tests green locally | Local stack and 3 tests (`9af53058`); refusal cases parked (MOB-115); faucet tests parked |
| 1b | Rayl's wallet and Spark: the regtest tests drive Rayl's own settlement and wallet code instead of the raw Breez SDK | Same cases as 1a, through Rayl's classes | Done: 12 tests (`641793aa`); found MOB-111, MOB-112, MOB-113, MOB-114 |
| 1c | Blink: Blink's `quickstart` stack, a script that creates a funded user and an API key, a configurable API URL in `BlinkApiClient` (test seam), integration tests of the Blink wallet | Same cases as 1a | Done: `tools/blink-stack` and 9 tests (`9e4a929f`), including held (PENDING) payments and the Stablesats wallet; no Rayl defect found |
| 1d | Nightly CI job running 1a–1c, with test-only credentials as GitHub secrets | Three nights green | Running (`nightly.yml`, own CI bank, local NWC and Blink stacks); all suites green in CI on 2026-11-06; MOB-114 (S2) makes the reinstall test fail intermittently |
| 1e | Blink staging as a second target for 1c, once Nicola's staging account is granted (requested 2026-11-06) | Nightly against staging | Waiting for account |
| **2** | **Maestro smoke flows** (§6.8) | | |
| 2a | E2E payment input by launch argument on both platforms (the `E2E` build types exist); test tags on the elements flows need | Input reaches the payment flow without the camera | Done (`0c5ba3b6`): `scan` launch argument; the NWC pay and rescan flow is green on the Android emulator and the iOS simulator from the same file |
| 2b | About ten flows, the same files for Android and iOS, against the 1a/1c stacks; replaces the stale `e2e/ios` | Green on emulator and simulator | |
| 2c | Flows in the nightly job | Three nights green | |
| **3** | **Robolectric** | | |
| 3a | Robolectric in the Android host tests (convention plugin) | One test runs in the PR gate | |
| 3b | Platform unit tests of Android-only logic: payment messages and `*PaymentStrings` (error text users see), `AmountFormatter.android`, connectivity, lifecycle, language | Each file has a test or a written reason why not | |
| 3c | Compose screen tests of the money screens: confirmation, amount entry, outcome and receipt, wallet removal | Amount, fee, confirmation and error shown correctly per state | |
| **4** | **CI fixes** | | |
| 4a | Android Lint in the PR gate (minSdk 24: API-level misuse crashes old phones) | Lint runs; findings fixed or baselined with a reason | |
| 4b | Release build (`assembleRelease` without signing secrets) in the PR gate | Builds in CI | |
| 4c | CI on `release/*` branches | Workflows trigger | |
| 4d | Android device tests (12) on a CI emulator, nightly | Green in CI | |
| 4e | iOS Keychain tests in an app-hosted XCTest target, nightly | Green in CI | |
| **5** | **Field visibility**: Play native debug symbols (MOB-105), Play vitals and Xcode Organizer crash review one week after each release | 1.1's first review done | |
| **6** | **Stored data:** tests that open data written by 1.1 (settings, credentials, pocket key copy, Spark vault, Hub) with the current code | A format change fails a test | |

Item 0 comes first only because it is time-critical and small. Everything else follows the order
agreed on 2026-11-06.

## Needed from Nicola

- Run the smoke checklist on the 1.1 store build, and hold the release until it passes (item 0).
- Blink staging account (item 1e), requested 2026-11-06.
- Start OrbStack when the Docker-based items begin (1a, 1c).
- Decisions on non-trivial defects that QA reports.

## Log

Executions that CI doesn't record (local stack runs, manual runs) go into
[test-log.md](test-log.md), newest first. Defects are only in Linear.
