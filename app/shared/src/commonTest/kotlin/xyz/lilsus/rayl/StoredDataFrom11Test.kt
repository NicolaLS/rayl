package xyz.lilsus.rayl

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rayl 1.1 is the first public release: every later version must read what it stored (test
 * project 2, item 6). These are 1.1's exact keys and values. If a test fails, the format
 * changed: read the old format too (migrate), don't change the fixture.
 */
class StoredDataFrom11Test {
    @Test
    fun theExternalWalletConnectedIn11IsStillTheWalletInUse() {
        // Unreadable, it would count as unowned and prepare() would delete its credentials.
        mapOf(
            "Blink" to ExternalWallet.Blink,
            "Nwc" to ExternalWallet.Nwc,
            "Spark" to ExternalWallet.Spark
        ).forEach { (stored, wallet) ->
            val settings = MapSettings("rayl.onboarded" to true, "rayl.wallet" to stored)
            assertFalse(usesRaylWallet(settings), "before carrying over, rayl.wallet=$stored")

            val card = RaylCards(settings).paying.value

            // It carries over as the card that pays, on 1.1's storage.
            assertEquals(RaylCard.legacy(wallet), card, "rayl.wallet=$stored")
            assertEquals(listOf(card), RaylCards(settings).cards.value)
            assertFalse(usesRaylWallet(settings))
        }
    }

    @Test
    fun the11WalletKeepsItsCredentialsThroughCleanup() = kotlinx.coroutines.test.runTest {
        val settings = MapSettings("rayl.onboarded" to true, "rayl.wallet" to "Nwc")
        val erased = mutableListOf<RaylCard>()

        RaylCards(settings).prepare({
            erased += it
            true
        })

        assertFalse(RaylCard.legacy(ExternalWallet.Nwc) in erased)
        assertEquals(RAYL_NWC, RaylCard.legacy(ExternalWallet.Nwc).nwcConfiguration())
    }

    @Test
    fun anAppOnboardedIn11WithoutAnExternalWalletUsesRaylsWallet() {
        assertTrue(usesRaylWallet(MapSettings("rayl.onboarded" to true)))
    }

    @Test
    fun theStoresAreTheOnes11WroteTo() {
        // Platform file and Keychain names: a new name starts empty, as if never connected.
        assertEquals("rayl_preferences", RAYL_PREFERENCES)
        assertEquals("rayl_blink_connection", RAYL_BLINK.walletPreferencesName)
        assertEquals("rayl_blink_credentials", RAYL_BLINK.credentialsName)
        assertEquals("rayl_nwc_connection", RAYL_NWC.walletPreferencesName)
        assertEquals("rayl_nwc_credentials", RAYL_NWC.credentialsName)
        assertEquals(RAYL_PREFERENCES, RAYL_SPARK.preferencesName)
        assertEquals(RAYL_PREFERENCES, RAYL_POCKET.preferencesName)
    }
}
