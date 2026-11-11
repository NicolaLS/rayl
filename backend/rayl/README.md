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

Testers can send feedback from the Hub's "Add widget" screen to `POST /v1/feedback`: a kind
(widget request, idea, problem, other), the platform, free text and an optional contact the tester
typed. Nothing else comes with it, not even the app version. It goes to the `feedback` table in
the same database and stays until deleted. Read it with:

```sh
tools/feedback/feedback.sh        # latest 50; `feedback.sh 200 widget` for more or one kind
```

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
