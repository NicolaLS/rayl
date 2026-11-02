package xyz.lilsus.raylsuite.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightningAddressTest {
    @Test
    fun parsesSupportedUserInputAndRejectsNonAddresses() {
        val supported =
            mapOf(
                "pay@lilsus.xyz" to "pay@lilsus.xyz",
                "lightning:LiLsUs+Tips@BliNk.sv" to "lilsus+tips@blink.sv",
                "http://JuM@BliNk.sv/" to "jum@blink.sv"
            )

        supported.forEach { (input, expected) ->
            assertEquals(expected, LightningAddress.parse(input)?.full)
        }
        listOf(
            "https://example.com/path",
            "pay@lilsus.xyz/path",
            "pay@localhost",
            "golol.de"
        ).forEach { input ->
            assertNull(LightningAddress.parse(input))
        }
    }
}
