# Usage dashboard

Grafana on your machine, reading Rayl's anonymous usage data from the backend's Postgres with a
read-only role. Nothing is hosted: start it when you want to look.

```sh
tools/usage-dashboard/connect                              # once, and to rotate the password
docker compose -f tools/usage-dashboard/compose.yaml up -d
open http://localhost:3000
docker compose -f tools/usage-dashboard/compose.yaml down  # when done
```

`connect` needs the Railway CLI, `psql`, and a public TCP proxy on the Postgres service. It
creates the role `grafana_reader`, which can only read the three usage-data tables, and writes its
connection to `.env` (gitignored).

The dashboard is `dashboards/usage-data.json`. Changes made in the Grafana UI are lost when the
container is removed: export the dashboard's JSON and replace the file to keep them. What the
events mean is in
[`TelemetryCatalog.kt`](../../core/rayl-api/src/commonMain/kotlin/xyz/lilsus/raylsuite/core/raylapi/TelemetryCatalog.kt).

To try it without production data, point `.env` at any Postgres that has the tables
(`RAYL_DB_HOST`, `RAYL_DB_PORT`, `RAYL_DB_NAME`, `RAYL_DB_USER`, `RAYL_DB_PASSWORD`,
`RAYL_DB_SSLMODE=disable`); from the container, the host machine is `host.docker.internal`.
