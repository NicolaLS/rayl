package xyz.lilsus.raylsuite.feature.paymentui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.lilsus.raylsuite.core.model.DisplayAmount
import xyz.lilsus.raylsuite.core.model.DisplayCurrency

class LnurlPayDisplayTest {
    @Test
    fun sanitizesUntrustedTextAndBoundsDescription() {
        val display =
            LnurlPayDisplay.fromUntrusted(
                domain = " PAY.EXAMPLE. ",
                description = "  Coffee\u202epj.exe\n<script>  " + "x".repeat(400)
            )

        assertNotNull(display)
        assertEquals("pay.example", display.domain)
        assertEquals(false, display.description.contains('\u202e'))
        assertEquals(false, display.description.contains('<'))
        assertEquals(280, display.description.length)
    }

    @Test
    fun rejectsMissingRequiredTextAndOmitsInvalidImage() {
        assertNull(LnurlPayDisplay.fromUntrusted("pay.example", null))

        val display =
            LnurlPayDisplay.fromUntrusted(
                domain = "pay.example",
                description = "Coffee",
                imagePngBase64 = "bm90IGEgcG5n"
            )

        assertNotNull(display)
        assertNull(display.image)
    }
}
