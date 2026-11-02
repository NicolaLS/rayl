package xyz.lilsus.rayl.providers.blink.integration.blink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BlinkCredentialStoreTest {
    @Test
    fun storesAndClearsOneCredentialSet() {
        val store = BlinkCredentialStore(TestSecureStringStore())
        val credentials =
            BlinkCredentials(
                apiKey = "blink_test",
                selectedFundingWallet =
                    BlinkFundingWallet("wallet-id", BlinkWalletCurrency.USD)
            )

        store.save(credentials)
        assertEquals(credentials, store.read())

        store.clear()
        assertNull(store.read())
    }

    @Test
    fun theApiKeyIsNeverPrinted() {
        // A log line or crash report that captures the object must not leak the key (MOB-95).
        val credentials = BlinkCredentials("blink_secret_key", BlinkFundingWallet("wallet-id", BlinkWalletCurrency.BTC))

        assertFalse("blink_secret_key" in credentials.toString(), credentials.toString())
    }
}
