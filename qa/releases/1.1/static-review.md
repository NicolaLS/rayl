# Static review for 1.1 (M8)

Reviewed on 2026-11-02 against `main`. Risks PR-11 (secrets exposed), PR-19 (dependency
change), PR-20 (release misconfigured).

## Secrets in logs and `toString()` (PR-11)

| Check | Result |
|---|---|
| Release-build logging | Payment-path timings (`RaylTiming`) and Blink GraphQL logging are enabled only in debug builds (Android `BuildConfig.DEBUG`, iOS `#if DEBUG`; Blink's flag defaults to off). Release builds log camera errors and a Keychain status code only. The regtest invoice log is limited to the debug environment. **OK** |
| `toString()` of credential holders | Pocket key, Spark credential and Breez config already redacted. Blink API key, NWC connection URI and Spark phrase did not: **MOB-95, fixed** (`eb5edce6`). |
| Secrets in the repository | No keystores, provisioning profiles or env files tracked; the Breez API key comes from the environment (`RAYL_BREEZ_API_KEY`) into gitignored `local.properties` / `Secrets.xcconfig`; Mainnet builds fail without it. **OK** |

## Release configuration (PR-20)

| Check | Result |
|---|---|
| Android release build | Minified and resource-shrunk, not debuggable, signed with the upload key (validated before `preReleaseBuild`). Debug tools (erase the pocket key) only with `BuildConfig.DEBUG`; on iOS only `#if DEBUG`. **OK** |
| Backup | `allowBackup="false"`, `fullBackupContent="false"`, and data extraction rules exclude every domain from cloud backup and device transfer. **OK** (so no encrypted credentials are restored without their Keystore key, see O-11) |
| Test builds | `e2e` and `benchmark` use their own application IDs and the debug signing key, and carry no Breez API key. **OK** |
| Version numbers | `main` still says 1.0.0, Android `versionCode = 1`, iOS build 1, while `release/1.0` already uses Android `versionCode = 2`. **Release checklist item:** the 1.1 cut needs versionName 1.1.0 and Android versionCode ≥ 3, plus iOS `MARKETING_VERSION` 1.1.0 and a higher `CURRENT_PROJECT_VERSION`. Play and App Store Connect would reject the upload otherwise. |

## Dependency pins (PR-19)

| Check | Result |
|---|---|
| Breez SDK on both platforms | Gradle, Xcode `exactVersion` and `Package.resolved` all pin 0.27.2. Only a comment kept them in sync, so `verifyBreezSdkPins` now checks it in the gate (`b2b31c2d`). Verified against a planted mismatch. |

## Upgrade from 1.0 (PR-12)

1.0 is in store review, so 1.1 must keep working with what 1.0 stores. Compared with
`release/1.0`: the storage names (`rayl_preferences`, `rayl_blink_*`, `rayl_nwc_*`), the
selected-wallet key `rayl.wallet` and its values (`Blink`, `Nwc`) are unchanged, and every
settings key 1.0 writes is still read, except 1.0's welcome flag and its "NWC onboarding
step in progress" key (at worst an onboarding starts over). A 1.0 user with Blink or NWC
connected gets that wallet mounted directly, without Rayl's onboarding. **OK statically;**
checklist U2/U3 test the real upgrade. From the day 1.0 is published, the pre-release rule
"no migrations" no longer covers data 1.0 writes.

Not covered here: Linked Specs and the `distribution/` documents (M9), and anything that
needs a running build (stage 3).
