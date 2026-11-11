package xyz.lilsus.raylsuite.backend.rayl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/** Like [PostgresTelemetryStoreTest]: runs against `RAYL_TEST_DATABASE_URL`, skipped without it. */
class PostgresFeedbackStoreTest {
    private val databaseUrl = System.getenv("RAYL_TEST_DATABASE_URL").orEmpty()

    @Test
    fun keepsMessagesAndCreatesItsTableAgainOnRestart() = runTest {
        assumeTrue("RAYL_TEST_DATABASE_URL is not set", databaseUrl.isNotBlank())
        val dataSource = PostgresTelemetryStore.connect(databaseUrl)
        try {
            dataSource.connection.use {
                it.createStatement().execute("drop table if exists feedback")
            }
            val store = PostgresFeedbackStore(dataSource)
            store.createTables()
            store.createTables()
            store.insert(FeedbackRow(1_794_321_462L, "idea", "ios", "Dark mode", null))
            store.insert(FeedbackRow(1_794_321_500L, "widget", "android", "Zaps", "me@example.com"))
            val rows = dataSource.connection.use { connection ->
                connection.createStatement().executeQuery(
                    "select kind, message, contact from feedback order by id"
                ).use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                listOf(
                                    result.getString(1),
                                    result.getString(2),
                                    result.getString(3)
                                )
                            )
                        }
                    }
                }
            }
            assertEquals(
                listOf(
                    listOf("idea", "Dark mode", null),
                    listOf("widget", "Zaps", "me@example.com")
                ),
                rows
            )
        } finally {
            dataSource.close()
        }
    }
}
