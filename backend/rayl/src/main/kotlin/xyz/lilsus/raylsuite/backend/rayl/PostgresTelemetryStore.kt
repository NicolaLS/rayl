package xyz.lilsus.raylsuite.backend.rayl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.sql.Timestamp
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Rayl's anonymous usage data in Postgres. Events are kept for [RAW_RETENTION_DAYS] days; before
 * that, [maintain] folds each day into per-day counts and percentiles, which are kept.
 *
 * Nothing about a request bounds how much is stored, so two limits do. Events stop being stored
 * once the three tables reach [maxStorageBytes], well short of the volume, and a day is folded
 * into at most [maxRollupVersions] app versions and [maxRollupGroups] groups per kind of event,
 * because the daily tables are never deleted (MOB-148).
 */
class PostgresTelemetryStore(
    private val dataSource: DataSource,
    private val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    private val maxRollupVersions: Int = DEFAULT_MAX_ROLLUP_VERSIONS,
    private val maxRollupGroups: Int = DEFAULT_MAX_ROLLUP_GROUPS,
    private val storageCheckIntervalMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = ::println
) : TelemetryStore {
    private val storageCheck = Mutex()
    private var storageCheckedAtMs: Long? = null
    private var storageFull = false

    suspend fun createTables() = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> SCHEMA.forEach(statement::execute) }
        }
    }

    override suspend fun insert(rows: List<TelemetryRow>) {
        if (rows.isEmpty()) return
        // Dropped, not refused: the app must not retry what this backend will not keep.
        if (!roomForMore()) return
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
     * False once the three tables have grown past [maxStorageBytes]. A size is a few catalogue
     * lookups, not free, so it is read at most every [storageCheckIntervalMs] and the answer is
     * reused in between. Both edges are logged, because nothing else would say that usage data
     * stopped arriving.
     */
    private suspend fun roomForMore(): Boolean = storageCheck.withLock {
        val now = clock()
        val checkedAt = storageCheckedAtMs
        if (checkedAt == null || now - checkedAt >= storageCheckIntervalMs) {
            storageCheckedAtMs = now
            val used = withContext(Dispatchers.IO) { usedBytes() }
            val full = used >= maxStorageBytes
            if (full != storageFull) {
                log(
                    if (full) {
                        "Usage data is no longer stored: $used bytes of $maxStorageBytes"
                    } else {
                        "Usage data is stored again: $used bytes of $maxStorageBytes"
                    }
                )
            }
            storageFull = full
        }
        !storageFull
    }

    /** Reads the catalogue, so a table that does not exist yet counts as nothing. */
    private fun usedBytes(): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(USED_BYTES).use { statement ->
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
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
                    statement.setInt(3, maxRollupVersions)
                    statement.setInt(4, maxRollupGroups)
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

        /** Two of the volume's five gigabytes, leaving the rest for feedback, WAL and vacuum. */
        private const val DEFAULT_MAX_STORAGE_BYTES = 2L * 1024 * 1024 * 1024

        /** Two platforms times the few versions ever in the wild at once, with room to spare. */
        private const val DEFAULT_MAX_ROLLUP_VERSIONS = 20

        /** A kind of event has at most a few dozen real dimension combinations in a day. */
        private const val DEFAULT_MAX_ROLLUP_GROUPS = 500
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

        private const val USED_BYTES = """
            select coalesce(sum(pg_total_relation_size(c.oid)), 0)
            from pg_class c
            join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = current_schema()
                and c.relname in (
                    'telemetry_events', 'telemetry_daily_counts', 'telemetry_daily_measures'
                )
        """

        /**
         * The window grouped as the daily tables hold it, with two caps, because those tables are
         * never deleted: a day keeps the app versions that sent the most and folds the rest into
         * `other`, and each kind of event keeps its commonest groups. Real traffic is far inside
         * both; invented versions and sprayed dimension combinations are what grow without end.
         * The order within a cap is fully determined, so folding a day again gives the same rows.
         *
         * The device model stays out of the daily tables: they are kept, and it is the one
         * free-form value.
         *
         * Parameters: the window's start and end, then the two caps.
         */
        private const val FOLDED = """
            with windowed as (
                select (received_hour at time zone 'UTC')::date as day, name, platform,
                    app_version, dimensions, measures
                from telemetry_events
                where received_hour >= ? and received_hour < ?
            ),
            named as (
                select day, app_version from (
                    select day, app_version,
                        row_number() over (
                            partition by day order by count(*) desc, app_version
                        ) as place
                    from windowed
                    group by day, app_version
                ) ranked
                where place <= ?
            ),
            folded as (
                select w.day, w.name, w.platform,
                    case when n.app_version is null then 'other' else w.app_version end
                        as app_version,
                    w.dimensions, w.measures
                from windowed w
                left join named n on n.day = w.day and n.app_version = w.app_version
            ),
            grouped as (
                select day, name, platform, app_version, dimensions, count(*) as events,
                    row_number() over (
                        partition by day, name
                        order by count(*) desc, app_version, platform, dimensions
                    ) as place
                from folded
                group by day, name, platform, app_version, dimensions
            )
        """

        private const val ROLL_UP_COUNTS = FOLDED + """
            insert into telemetry_daily_counts (day, name, platform, app_version, dimensions, events)
            select day, name, platform, app_version, dimensions, events
            from grouped
            where place <= ?
            on conflict (day, name, platform, app_version, dimensions)
            do update set events = excluded.events
        """

        private const val ROLL_UP_MEASURES = FOLDED + """
            insert into telemetry_daily_measures (
                day, name, platform, app_version, dimensions, measure, samples, p50, p75, p90
            )
            select f.day, f.name, f.platform, f.app_version, f.dimensions, measure.key, count(*),
                percentile_cont(0.5) within group (order by measure.value::double precision),
                percentile_cont(0.75) within group (order by measure.value::double precision),
                percentile_cont(0.9) within group (order by measure.value::double precision)
            from folded f
            join grouped g on g.day = f.day and g.name = f.name and g.platform = f.platform
                and g.app_version = f.app_version and g.dimensions = f.dimensions
                and g.place <= ?
            cross join lateral jsonb_each_text(f.measures) as measure
            group by 1, 2, 3, 4, 5, 6
            on conflict (day, name, platform, app_version, dimensions, measure)
            do update set samples = excluded.samples, p50 = excluded.p50, p75 = excluded.p75,
                p90 = excluded.p90
        """

        private const val DELETE_EXPIRED = "delete from telemetry_events where received_hour < ?"
    }
}
