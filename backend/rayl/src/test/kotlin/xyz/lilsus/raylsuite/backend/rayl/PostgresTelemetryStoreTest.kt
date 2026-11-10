package xyz.lilsus.raylsuite.backend.rayl

import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun storeTest(block: suspend (PostgresTelemetryStore) -> Unit) = runTest {
        assumeTrue("RAYL_TEST_DATABASE_URL is not set", databaseUrl.isNotBlank())
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "drop table if exists telemetry_events, telemetry_daily_counts, telemetry_daily_measures"
                )
            }
        }
        val store = PostgresTelemetryStore(dataSource)
        store.createTables()
        // A restarted server creates them again.
        store.createTables()
        block(store)
    }

    @AfterTest
    fun close() {
        if (databaseUrl.isNotBlank()) dataSource.close()
    }

    private fun payment(
        receivedHour: Long,
        outcome: String,
        settleMs: Long,
        deviceModel: String = "Pixel 8"
    ) = TelemetryRow(
        receivedHour = receivedHour,
        platform = "android",
        appVersion = "1.2.0",
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
}
