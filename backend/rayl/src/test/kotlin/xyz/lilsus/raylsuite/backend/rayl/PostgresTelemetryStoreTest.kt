package xyz.lilsus.raylsuite.backend.rayl

import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * Runs against a real Postgres named by `RAYL_TEST_DATABASE_URL` (CI starts one) and is skipped
 * without it: the store is SQL, and only a database can say whether the SQL is right.
 *
 * Locally:
 * `docker run --rm -d -p 54329:5432 -e POSTGRES_PASSWORD=test postgres:17`, then
 * `RAYL_TEST_DATABASE_URL=postgresql://postgres:test@localhost:54329/postgres ./gradlew :backend:rayl:test`.
 */
class PostgresTelemetryStoreTest {
    private val databaseUrl = System.getenv("RAYL_TEST_DATABASE_URL").orEmpty()
    private val dataSource by lazy { PostgresTelemetryStore.connect(databaseUrl) }

    // 2026-11-10T00:00:00Z
    private val today = 1_794_268_800L
    private val day = 86_400L

    private fun storeTest(
        store: (DataSource) -> PostgresTelemetryStore = ::PostgresTelemetryStore,
        block: suspend (PostgresTelemetryStore) -> Unit
    ) = runTest {
        assumeTrue("RAYL_TEST_DATABASE_URL is not set", databaseUrl.isNotBlank())
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "drop table if exists telemetry_events, telemetry_daily_counts, telemetry_daily_measures"
                )
            }
        }
        val created = store(dataSource)
        created.createTables()
        // A restarted server creates them again.
        created.createTables()
        block(created)
    }

    @AfterTest
    fun close() {
        if (databaseUrl.isNotBlank()) dataSource.close()
    }

    private fun payment(
        receivedHour: Long,
        outcome: String,
        settleMs: Long,
        deviceModel: String = "Pixel 8",
        appVersion: String = "1.2.0"
    ) = TelemetryRow(
        receivedHour = receivedHour,
        platform = "android",
        appVersion = appVersion,
        osMajor = 16,
        deviceModel = deviceModel,
        name = "payment",
        dimensions = mapOf("wallet" to "rayl", "outcome" to outcome),
        measures = mapOf("submit_to_outcome_ms" to settleMs)
    )

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        dataSource.connection.use { connection: Connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList { while (rows.next()) add(read(rows)) }
                }
            }
        }

    @Test
    fun foldsEachCompleteDayIntoCountsAndPercentiles() = storeTest { store ->
        val yesterday = today - day
        store.insert(
            listOf(
                payment(yesterday + 3_600, "paid", 1_000),
                payment(yesterday + 7_200, "paid", 2_000, deviceModel = "SM-G970F"),
                payment(yesterday + 7_200, "paid", 3_000),
                payment(yesterday + 7_200, "failed", 9_000),
                // Today is not complete yet and stays out.
                payment(today + 3_600, "paid", 50_000)
            )
        )

        store.maintain(nowSeconds = today + 4_000)
        // Running it again must not double anything.
        store.maintain(nowSeconds = today + 4_000)

        assertEquals(
            listOf("2026-11-09 failed 1", "2026-11-09 paid 3"),
            query(
                "select day, dimensions->>'outcome', events from telemetry_daily_counts order by 2"
            ) { "${it.getString(1)} ${it.getString(2)} ${it.getLong(3)}" }
        )
        assertEquals(
            listOf(
                "failed submit_to_outcome_ms 1 9000.0 9000.0 9000.0",
                "paid submit_to_outcome_ms 3 2000.0 2500.0 2800.0"
            ),
            query(
                "select dimensions->>'outcome', measure, samples, p50, p75, p90 " +
                    "from telemetry_daily_measures order by 1"
            ) {
                "${it.getString(1)} ${it.getString(2)} ${it.getLong(3)} " +
                    "${it.getDouble(4)} ${it.getDouble(5)} ${it.getDouble(6)}"
            }
        )
    }

    @Test
    fun deletesEventsAfterNinetyDaysAndKeepsTheirDailyFigures() = storeTest { store ->
        val old = today - 91 * day
        store.insert(listOf(payment(old, "paid", 1_000)))
        // Folded while the event was recent.
        store.maintain(nowSeconds = old + day + 60)
        store.insert(listOf(payment(today - 89 * day, "paid", 2_000)))

        store.maintain(nowSeconds = today)

        assertEquals(
            listOf((today - 89 * day) * 1_000),
            query("select received_hour from telemetry_events") { it.getTimestamp(1).time }
        )
        assertEquals(
            listOf(1L),
            query("select events from telemetry_daily_counts") { it.getLong(1) }
        )
    }

    @Test
    fun storesNothingMoreOnceTheTablesReachTheBudget() = storeTest { seeded ->
        seeded.insert(listOf(payment(today + 3_600, "paid", 1_000)))
        val used = query("select pg_total_relation_size('telemetry_events')") { it.getLong(1) }
            .single()
        assertTrue(used > 0, "a stored row should take space")

        // A budget the tables have already reached. Nothing is stored and nothing fails, so the
        // app is still answered 204 and does not retry what this backend will not keep.
        val full = PostgresTelemetryStore(dataSource, maxStorageBytes = used, log = {})
        full.insert(listOf(payment(today + 3_600, "paid", 2_000)))
        assertEquals(listOf(1L), eventCount())

        // Room again, as after the daily delete: events are stored as before.
        val roomy = PostgresTelemetryStore(dataSource, maxStorageBytes = used + MEGABYTE, log = {})
        roomy.insert(listOf(payment(today + 3_600, "paid", 3_000)))
        assertEquals(listOf(2L), eventCount())
    }

    @Test
    fun aDayKeepsTheVersionsThatSentMostAndFoldsTheRestIntoOther() = storeTest(
        store = { PostgresTelemetryStore(it, maxRollupVersions = 2, log = {}) }
    ) { store ->
        val yesterday = today - day
        store.insert(
            List(3) { payment(yesterday + 3_600, "paid", 1_000, appVersion = "1.2.0") } +
                List(2) { payment(yesterday + 3_600, "paid", 1_000, appVersion = "1.3.0") } +
                // Versions no app ever shipped. Each would be its own group, kept for ever.
                payment(yesterday + 3_600, "paid", 1_000, appVersion = "999.999.999-aaaaaaaa") +
                payment(yesterday + 3_600, "paid", 1_000, appVersion = "999.999.998-bbbbbbbb")
        )

        store.maintain(nowSeconds = today + 4_000)
        // Folding the day again gives the same rows.
        store.maintain(nowSeconds = today + 4_000)

        assertEquals(listOf("1.2.0 3", "1.3.0 2", "other 2"), dailyCounts("app_version"))
        assertEquals(listOf("1.2.0 3", "1.3.0 2", "other 2"), dailyMeasures("app_version"))
    }

    @Test
    fun aKindOfEventKeepsOnlyItsCommonestGroups() = storeTest(
        store = { PostgresTelemetryStore(it, maxRollupGroups = 2, log = {}) }
    ) { store ->
        val yesterday = today - day
        store.insert(
            List(3) { payment(yesterday + 3_600, "paid", 1_000) } +
                List(2) { payment(yesterday + 3_600, "failed", 1_000) } +
                // One of a kind, as a sprayed combination is: not worth keeping for ever.
                payment(yesterday + 3_600, "unknown", 1_000) +
                payment(yesterday + 3_600, "rejected", 1_000)
        )

        store.maintain(nowSeconds = today + 4_000)

        assertEquals(listOf("failed 2", "paid 3"), dailyCounts("dimensions->>'outcome'"))
        assertEquals(listOf("failed 2", "paid 3"), dailyMeasures("dimensions->>'outcome'"))
    }

    private fun eventCount() = query("select count(*) from telemetry_events") { it.getLong(1) }

    private fun dailyCounts(column: String) =
        query("select $column, events from telemetry_daily_counts order by 1") {
            "${it.getString(1)} ${it.getLong(2)}"
        }

    private fun dailyMeasures(column: String) =
        query("select $column, samples from telemetry_daily_measures order by 1") {
            "${it.getString(1)} ${it.getLong(2)}"
        }

    private companion object {
        const val MEGABYTE = 1024L * 1024
    }
}
