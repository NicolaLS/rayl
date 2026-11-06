# Local Blink stack

Blink's backend on regtest, for the Blink wallet integration tests (qa/test-plan.md §6.9). It is
Blink's own `quickstart` (about 30 containers: Blink's API, Ory, LND, Bria, Stablesats, …),
fetched from github.com/GaloyMoney/blink at the commit pinned in `bin/_common`. Next to Blink's
LND there is an outside LND node with a channel to Blink; tests pay its invoices.

```sh
tools/blink-stack/bin/up      # fetch the quickstart if needed, start from scratch (~30 s once images are pulled)
./gradlew :providers:blink:integration:blink:walletIntegrationTest
tools/blink-stack/bin/down
```

`bin/fixture` creates what the tests need: a new Blink user with an API key (login code
`000000`, the quickstart's), funded over Lightning from the outside node; outside-node invoices,
including hold invoices; balances. Run it without arguments for its commands. `bin/up` also
starts `bin/helper.py` on `127.0.0.1:4476`, which runs it over HTTP for Maestro flows.

Needs Docker (OrbStack on macOS), `git`, `jq`, `curl`, `openssl`, `xxd`. The API is at
`http://127.0.0.1:4475/graphql` (`RAYL_BLINK_API_PORT`); `compose.rayl.yml` publishes no other
port, so the stack runs next to other local services.

Blink's images are amd64 only; on Apple silicon they run emulated, which OrbStack does by
itself.

Blink staging (signet) is the second target once Rayl's staging account exists (test project 2,
item 1e).
