package xyz.lilsus.raylsuite.core.camera

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestQrInputTest {
    private val scheduled = mutableListOf<() -> Unit>()
    private val schedule = { _: Long, block: () -> Unit ->
        scheduled += block
        TestQrInput.Cancel { scheduled.remove(block) }
    }

    @AfterTest
    fun reset() = TestQrInput.install(null)

    @Test
    fun theFirstScannerReadsTheValueOnceAndLaterOnesReadNothing() {
        TestQrInput.install("lnbcrt1invoice")
        val reads = mutableListOf<String>()

        TestQrInput.scannerOrNull(schedule)!!.start(onQrCodeScanned = { reads += it })
        assertEquals(emptyList(), reads, "Read during start()")
        runScheduled()
        TestQrInput.scannerOrNull(schedule)!!.start(onQrCodeScanned = { reads += it })
        runScheduled()

        assertEquals(listOf("lnbcrt1invoice"), reads)
    }

    @Test
    fun aScannerStoppedBeforeItReadsLeavesTheValueForTheNextOne() {
        // The payment screen stops and restarts its scanner as it becomes inactive and active.
        TestQrInput.install("lnbcrt1invoice")
        val reads = mutableListOf<String>()

        TestQrInput.scannerOrNull(schedule)!!.apply {
            start(onQrCodeScanned = { reads += it })
            stop()
        }
        runScheduled()
        TestQrInput.scannerOrNull(schedule)!!.start(onQrCodeScanned = { reads += it })
        runScheduled()

        assertEquals(listOf("lnbcrt1invoice"), reads)
    }

    @Test
    fun withoutAValueTheFakeCameraReadsNothing() {
        TestQrInput.install(null)

        TestQrInput.scannerOrNull(schedule)!!.start(onQrCodeScanned = { error("Read $it") })

        assertTrue(scheduled.isEmpty())
    }

    private fun runScheduled() {
        val blocks = scheduled.toList()
        scheduled.clear()
        blocks.forEach { it() }
    }
}
