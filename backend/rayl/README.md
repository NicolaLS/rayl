# Rayl backend

Serves `GET /v1/bitcoin/prices` ([wire contract](../../core/rayl-api)). Every 30 seconds it polls
mempool.space, Coinbase, Kraken and blockchain.info, and it answers with the per-currency
median of the quotes that are at most five minutes old. It returns 503 when no source is fresh.

`GET /v1/bitcoin/prices/history?currency=USD` serves the last 24 hours in 15-minute steps for the
Hub's exchange rate widget. It comes from Kraken's candles for the seven currencies the app can
display, fetched on demand and cached for five minutes.

It also accepts the app's anonymous usage data on `POST /v1/telemetry`. Everything a batch can
contain is listed in [`TelemetryCatalog.kt`](../../core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt);
the backend drops the rest, stores no address or identifier, and cuts the arrival time to the
hour. Events go to the Postgres database named by `DATABASE_URL` (without it they are accepted
and dropped). They are deleted after 90 days; per-day counts and percentiles are kept.

`GET /v1/hub/widgets` says which Hub widgets are turned off, from the `DISABLED_HUB_WIDGETS`
variable: widget definition ids, comma-separated, for example `local.claro` or
`local.claro,local.ppq`. The app hides them from the gallery and shows a placed one as
"Unavailable", without its service's name or mark, until it is turned on again. Apps ask at most
every five minutes while the Hub is on screen and keep the last answer, so it also holds offline.
To turn a widget off or on, set the variable on Railway; that redeploys the service, and apps pick
it up within about five minutes after that. The ids are in `LocalHubWidgets` in
`feature/payment-hub`.

Testers can send feedback from the Hub's "Add widget" screen to `POST /v1/feedback`: a kind
(widget request, idea, problem, other), the platform, free text and an optional contact the tester
typed. Nothing else comes with it, not even the app version. It goes to the `feedback` table in
the same database and stays until deleted. Read it with:

```sh
tools/feedback/feedback.sh        # latest 50; `feedback.sh 200 widget` for more or one kind
```

## Rate limits

Usage data and feedback are limited per sender first (20 batches and 5 messages a minute), then
over all senders (600 and 30 a minute). A sender over its own limit is refused before it can use
up what the others share. Prices are not limited; they are served from memory.

Both accept only `application/json`, and refuse anything else before the limits count it. A web
page can make its visitors' browsers send text or a form to any site without asking, but not JSON,
so it cannot turn them into senders.

The sender is the address Railway's edge reports in `X-Real-IP`, with an IPv6 address cut to its
/64. It is held in the limiter's memory for about a minute and is never logged or stored. The
limiters live in the one running instance; with more than one replica each would count on its own.

## What is kept

A request says nothing about how much it stores, so two limits bound that instead. A batch holds
at most 100 events, what one app buffers in a process, which is about 60 kB stored; at the limit
over all senders that is still more a minute than the 90-day expiry removes, so events stop being
stored once the three usage-data tables reach 3 GB of the volume's 5 GB. Requests are still
answered 204: an app must not retry what this backend will not keep. The rest of the volume is
not spare. A Postgres whose volume is full cannot even delete, because a delete writes WAL first:
in a test it crashed on the cleanup and stayed down. The 2 GB keep it able to clean up, and keep
feedback working. `tools/daily/daily.sh` says how full it is.

The per-day figures are never deleted, so a day is folded into at most the 20 app versions that
sent the most, the rest together as `other`, and the 500 commonest groups per kind of event. Real
traffic is far inside both; invented versions and sprayed dimension combinations are what would
otherwise grow the kept tables without end.

What this does not stop: many addresses together can still reach the limits over all senders,
which blocks usage data and feedback for everyone while it lasts. That is accepted. Anonymous
data cannot be tied to an installation, so there is nothing stronger to limit by.

Run locally on `127.0.0.1:8080`:

```sh
./gradlew :backend:rayl:run
```

Deploy to Railway (project `rayl-backend`, service `rayl`). The image packages the locally
built distribution, so Railway never configures the Gradle project:

```sh
./gradlew :backend:rayl:installDist
railway up backend/rayl --path-as-root --no-gitignore \
  -p 9bbf2625-4edc-4a92-bc7c-b3e9f6c840be -s rayl -e production --detach
```
