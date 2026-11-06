# Field review (one week after each release)

About 15 minutes, one week after a release reaches users, and again after four weeks for a
minor release. It catches what no test environment shows: crashes and ANRs on real devices, OS
versions and networks. Record it in `qa/releases/<version>/runs/<date>-field-review.md`. Each new
crash or ANR cluster is filed in Linear (`MOB`, label `Bug`) with its stack trace.

| ID | Where | Check |
|---|---|---|
| F1 | Play Console → Android vitals → Overview | User-perceived crash rate and ANR rate below Play's bad-behaviour thresholds (1.09 % and 0.47 %); compare with the previous release |
| F2 | Play Console → Android vitals → Crashes and ANRs | Every cluster new in this version: open it. Native ones (Breez, JNA, libsodium) must show function names; raw addresses mean the bundle lacked debug symbols (MOB-105) |
| F3 | Xcode → Organizer → Crashes (and Energy, Hangs) for the version | Every new crash or hang: symbolicated, filed |
| F4 | App Store Connect → Ratings and Reviews; Play Console → Ratings and reviews | Reviews since the release that mention payments, balance, a wallet or a crash: filed or answered |
| F5 | Both | Android versions and devices in the crash list: any below API 26 or a specific maker? The nightly device tests run on API 24 and 34 only |

A crash in a payment path (scan, confirmation, sending, result) is S1/S2: tell Nicola the same
day, with the version, the affected share of users, and whether a staged rollout should stop.
