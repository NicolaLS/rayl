# Usage dashboard

Grafana on your machine, reading Rayl's anonymous usage data from the backend's Postgres with a
read-only role. Nothing is hosted: start it when you want to look.

```sh
tools/usage-dashboard/connect      # opens the dashboard; Ctrl+C closes everything
open http://localhost:3000
```

`connect` reaches the database through an encrypted tunnel of the Railway CLI
(`railway connect Postgres --tunnel-only`), so the database needs no public endpoint. It holds the
tunnel open and runs Grafana until you press Ctrl+C. The first run creates the role
`grafana_reader`, which can only read the three usage-data tables, and writes its password to
`.env` (gitignored); `connect --rotate` replaces the password. It needs the Railway CLI (logged
in), `psql` (`brew install libpq`) and Docker.

The dashboard is `dashboards/usage-data.json`. Changes made in the Grafana UI are lost when the
container is removed: export the dashboard's JSON and replace the file to keep them. What the
events mean is in
[`TelemetryCatalog.kt`](../../core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt).

To try it without production data, point `.env` at any Postgres that has the tables
(`RAYL_DB_HOST`, `RAYL_DB_PORT`, `RAYL_DB_NAME`, `RAYL_DB_USER`, `RAYL_DB_PASSWORD`,
`RAYL_DB_SSLMODE=disable`); from the container, the host machine is `host.docker.internal`.
