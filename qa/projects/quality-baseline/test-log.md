# Test log: quality baseline

Runs that CI does not record (local stack runs, manual runs, PIT), newest first.

| Date | What | Where | Build | Result |
|---|---|---|---|---|
| 2026-11-07 | NWC wallet integration tests (10), incl. the restored refusal and amountless cases | `tools/nwc-stack` with cln-nip47 v0.2.2 | `2f65c3c2` | 10/10 passed |
| 2026-11-07 | Raw relay subscription during a budget refusal (MOB-115) | `tools/nwc-stack`, cln-nip47 v0.1.9 and v0.2.2 | `3b5d12ee` | The wallet replies in the same second, with an extra `d` tag that Rayl dropped; after the fix Rayl gets QUOTA_EXCEEDED |
| 2026-11-07 | NWC wallet integration tests (6) after the 30 s limit (MOB-9) | `tools/nwc-stack`, cln-nip47 v0.1.9 | `3b5d12ee` (before commit) | 6/6 passed |
| 2026-11-06 | Android device tests (12) | Emulator `rayl_qa_api36` (Android 16), MacBook M4 | `main` at `0b322cfa` | 12/12 passed |
| 2026-11-06 | Maestro, all 10 flows | Android emulator `rayl_qa_api36`, local NWC and Blink stacks | E2E build at `f6ae54bb` | 10/10 passed (the 2 iOS-only flows are no-ops), 7 min 14 s |
| 2026-11-06 | Maestro, all 10 flows | iOS simulator iPhone 17 (iOS 27.1), local NWC and Blink stacks | iosAppE2E at `f6ae54bb` | 7/10, then 10/10 after three fixes (keypad IDs on iOS, language by launch argument, back-to-Rayl check) |
| 2026-11-06 | Blink wallet integration tests (9) | `tools/blink-stack` from scratch, twice | `9e4a929f` | 9/9 passed both times; mutant (no polling of a pending payment) fails 2 |
| 2026-11-06 | NWC wallet integration tests (3) | `tools/nwc-stack` | `9af53058` | 3/3 passed |
| 2026-11-06 | Maestro pay-and-rescan on the stripped native libraries | Android emulator | E2E build at `d3b4beb2` | Passed (onboarding loads Breez) |
