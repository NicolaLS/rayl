package xyz.lilsus.raylsuite.backend.rayl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Rayl's anonymous usage data in Postgres. Events are kept for [RAW_RETENTION_DAYS] days; before
 * that, [maintain] folds each day into per-day counts and percentiles, which are kept.
 */
class PostgresTelemetryStore(private val dataSource: DataSource) : TelemetryStore {
    suspend fun createTables() = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> SCHEMA.forEach(statement::execute) }
        }
    }

    override suspend fun insert(rows: List<TelemetryRow>) {
        if (rows.isEmpty()) return
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(INSERT).use { statement ->
                    rows.forEach { row ->
                        statement.setTimestamp(1, Timestamp(row.receivedHour * 1_000))
                        statement.setString(2, row.platform)
                        statement.setString(3, row.appVersion)
                        statement.setInt(4, row.osMajor)
                        statement.setString(5, row.deviceModel)
                        statement.setString(6, row.name)
                        statement.setString(7, Json.encodeToString(DIMENSIONS, row.dimensions))
                        statement.setString(8, Json.encodeToString(MEASURES, row.measures))
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }
    }

    /**
     * Folds the last [ROLLUP_DAYS] complete UTC days into the daily tables, then deletes events
     * older than [RAW_RETENTION_DAYS] days. Running it again changes nothing.
     */
    suspend fun maintain(nowSeconds: Long) = withContext(Dispatchers.IO) {
        val today = nowSeconds / SECONDS_PER_DAY * SECONDS_PER_DAY
        val from = Timestamp((today - ROLLUP_DAYS * SECONDS_PER_DAY) * 1_000)
        val until = Timestamp(today * 1_000)
        val expired = Timestamp((nowSeconds - RAW_RETENTION_DAYS * SECONDS_PER_DAY) * 1_000)
        dataSource.connection.use { connection ->
            listOf(ROLL_UP_COUNTS, ROLL_UP_MEASURES).forEach { sql ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setTimestamp(1, from)
                    statement.setTimestamp(2, until)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(DELETE_EXPIRED).use { statement ->
                statement.setTimestamp(1, expired)
                statement.executeUpdate()
            }
        }
    }

    companion object {
        const val RAW_RETENTION_DAYS = 90L
        private const val ROLLUP_DAYS = 7L
        private const val SECONDS_PER_DAY = 86_400L
        private val DIMENSIONS = MapSerializer(String.serializer(), String.serializer())
        private val MEASURES = MapSerializer(String.serializer(), Long.serializer())

        /** [databaseUrl] as Railway provides it: `postgresql://user:password@host:port/database`. */
        fun connect(databaseUrl: String): HikariDataSource {
            val uri = URI(databaseUrl)
            val (user, password) = uri.userInfo.split(":", limit = 2)
            return HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}"
                    username = user
                    this.password = password
                    maximumPoolSize = 4
                    // The server must start, and serve prices, while the database is away.
                    initializationFailTimeout = -1
                }
            )
        }

        private val SCHEMA = listOf(
            """
            create table if not exists telemetry_events (
                received_hour timestamptz not null,
                platform text not null,
                app_version text not null,
                os_major integer not null,
                device_model text not null,
                name text not null,
                dimensions jsonb not null,
                measures jsonb not null
            )
            """,
            """
            create index if not exists telemetry_events_name_hour
                on telemetry_events (name, received_hour)
            """,
            """
            create table if not exists telemetry_daily_counts (
                day date not null,
                name text not null,
                platform text not null,
                app_version text not null,
                dimensions jsonb not null,
                events bigint not null,
                primary key (day, name, platform, app_version, dimensions)
            )
            """,
            """
            create table if not exists telemetry_daily_measures (
                day date not null,
                name text not null,
                platform text not null,
                app_version text not null,
                dimensions jsonb not null,
                measure text not null,
                samples bigint not null,
                p50 double precision not null,
                p75 double precision not null,
                p90 double precision not null,
                primary key (day, name, platform, app_version, dimensions, measure)
            )
            """
        )

        private const val INSERT = """
            insert into telemetry_events (
                received_hour, platform, app_version, os_major, device_model, name, dimensions,
                measures
            ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
        """

        // The device model stays out of the daily tables: they are kept, and it is the one
        // free-form value.
        private const val ROLL_UP_COUNTS = """
            insert into telemetry_daily_counts (day, name, platform, app_version, dimensions, events)
            select (received_hour at time zone 'UTC')::date, name, platform, app_version,
                dimensions, count(*)
            from telemetry_events
            where received_hour >= ? and received_hour < ?
            group by 1, 2, 3, 4, 5
            on conflict (day, name, platform, app_version, dimensions)
            do update set events = excluded.events
        """

        private const val ROLL_UP_MEASURES = """
            insert into telemetry_daily_measures (
                day, name, platform, app_version, dimensions, measure, samples, p50, p75, p90
            )
            select (received_hour at time zone 'UTC')::date, name, platform, app_version,
                dimensions, measure.key, count(*),
                percentile_cont(0.5) within group (order by measure.value::double precision),
                percentile_cont(0.75) within group (order by measure.value::double precision),
                percentile_cont(0.9) within group (order by measure.value::double precision)
            from telemetry_events
            cross join lateral jsonb_each_text(measures) as measure
            where received_hour >= ? and received_hour < ?
            group by 1, 2, 3, 4, 5, 6
            on conflict (day, name, platform, app_version, dimensions, measure)
            do update set samples = excluded.samples, p50 = excluded.p50, p75 = excluded.p75,
                p90 = excluded.p90
        """

        private const val DELETE_EXPIRED = "delete from telemetry_events where received_hour < ?"
    }
}
