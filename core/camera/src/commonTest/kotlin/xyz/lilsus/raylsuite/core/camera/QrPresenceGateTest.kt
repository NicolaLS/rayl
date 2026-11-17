package xyz.lilsus.raylsuite.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class QrPresenceGateTest {
    private val nothing = emptyList<String>()

    @Test
    fun sameVisibleQrEmitsOnceAfterScannerRestart() {
        val gate = QrPresenceGate(absentObservationsToRearm = 2)

        assertEquals(listOf("invoice"), gate.observe(listOf("invoice")))
        assertEquals(nothing, gate.observe(listOf("invoice")))

        gate.reset()

        assertEquals(listOf("invoice"), gate.observe(listOf("invoice")))
        assertEquals(nothing, gate.observe(listOf("invoice")))
    }

    @Test
    fun qrRearmsOnlyAfterItDisappears() {
        val gate = QrPresenceGate(absentObservationsToRearm = 2)

        assertEquals(listOf("invoice"), gate.observe(listOf("invoice")))
        assertEquals(nothing, gate.observe(nothing))
        assertEquals(nothing, gate.observe(listOf("invoice")))
        assertEquals(nothing, gate.observe(nothing))
        assertEquals(nothing, gate.observe(nothing))

        assertEquals(listOf("invoice"), gate.observe(listOf("invoice")))
    }

    @Test
    fun differentQrEmitsImmediately() {
        val gate = QrPresenceGate()

        assertEquals(listOf("first"), gate.observe(listOf("first")))
        assertEquals(listOf("second"), gate.observe(listOf("second")))
        assertEquals(nothing, gate.observe(listOf("second")))
    }

    @Test
    fun aCodeNextToOneAlreadyInViewIsHandedOnWhenItIsFirstRead() {
        // The menu link is larger and read first; the invoice beside it must not wait for the
        // menu to leave the picture.
        val gate = QrPresenceGate()

        assertEquals(listOf("menu"), gate.observe(listOf("menu")))
        assertEquals(listOf("invoice"), gate.observe(listOf("menu", "invoice")))
        assertEquals(nothing, gate.observe(listOf("menu", "invoice")))
    }

    @Test
    fun twoCodesReadInTurnsAreEachHandedOnOnce() {
        // A reader that gets only one of two codes per frame used to hand both on again and again.
        val gate = QrPresenceGate()

        assertEquals(listOf("first"), gate.observe(listOf("first")))
        assertEquals(listOf("second"), gate.observe(listOf("second")))
        assertEquals(nothing, gate.observe(listOf("first")))
        assertEquals(nothing, gate.observe(listOf("second")))
    }
}
