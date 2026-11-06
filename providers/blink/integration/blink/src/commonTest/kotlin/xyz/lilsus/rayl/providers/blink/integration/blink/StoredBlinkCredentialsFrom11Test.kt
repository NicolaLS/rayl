package xyz.lilsus.rayl.providers.blink.integration.blink

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rayl 1.1 is the first public release: later versions must read the Blink connection it stored
 * (test project 2, item 6). The fixture is the JSON 1.1 writes. If this fails, the format
 * changed: read the old format too, don't change the fixture.
 */
class StoredBlinkCredentialsFrom11Test {
    @Test
    fun readsTheApiKeyAndTheChosenFundingWallet11Stored() {
        val store = TestSecureStringStore()
        store.putString(
            "credentials",
            """{"apiKey":"blink_abc","selectedFundingWallet":{"id":"wallet-usd","currency":"USD"}}"""
        )

        assertEquals(
            BlinkCredentials(
                apiKey = "blink_abc",
                selectedFundingWallet = BlinkFundingWallet("wallet-usd", BlinkWalletCurrency.USD)
            ),
            BlinkCredentialStore(store).read()
        )
    }

    @Test
    fun readsAConnectionWithoutAChosenFundingWallet() {
        val store = TestSecureStringStore()
        store.putString("credentials", """{"apiKey":"blink_abc","selectedFundingWallet":null}""")

        assertEquals(
            BlinkCredentials(apiKey = "blink_abc", selectedFundingWallet = null),
            BlinkCredentialStore(store).read()
        )
    }
}
